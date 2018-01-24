package hmda.persistence.processing

import akka.NotUsed
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.pattern.{ ask, pipe }
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import hmda.model.fi.SubmissionId
import hmda.model.fi.lar.LoanApplicationRegister
import hmda.model.fi.ts.TransmittalSheet
import hmda.model.institution.Institution
import hmda.model.validation._
import hmda.persistence.HmdaSupervisor.{ FindHmdaFiling, FindProcessingActor }
import hmda.persistence.institutions.InstitutionPersistence
import hmda.persistence.PaginatedResource
import hmda.persistence.messages.CommonMessages._
import hmda.persistence.messages.commands.institutions.InstitutionCommands.GetInstitutionById
import hmda.persistence.model.HmdaPersistentActor
import hmda.persistence.processing.ProcessingMessages.{ BeginValidation, CompleteValidation, ValidationCompleted, ValidationCompletedWithErrors }
import hmda.util.SourceUtils
import hmda.validation.context.ValidationContext
import hmda.validation.engine._
import hmda.validation.engine.lar.LarEngine
import hmda.validation.engine.ts.TsEngine
import hmda.validation.rules.lar.`macro`.MacroEditTypes._
import hmda.persistence.processing.HmdaQuery._
import hmda.persistence.messages.events.processing.CommonHmdaValidatorEvents._
import hmda.persistence.messages.events.processing.HmdaFileParserEvents.{ LarParsed, TsParsed }
import hmda.persistence.messages.events.processing.HmdaFileValidatorEvents._
import hmda.persistence.messages.events.validation.SubmissionLarStatsEvents.MacroStatsUpdated
import hmda.persistence.model.HmdaSupervisorActor.FindActorByName
import hmda.persistence.processing.SubmissionManager.GetActorRef
import hmda.validation.stats.SubmissionLarStats.PersistStatsForMacroEdits
import hmda.validation.stats.ValidationStats.AddSubmissionTaxId
import hmda.validation.stats.SubmissionLarStats

import scala.util.Try
import scala.concurrent.duration._

object HmdaFileValidator {

  val name = "HmdaFileValidator"

  case class ValidationStarted(submissionId: SubmissionId) extends Event
  case class ValidateMacro(source: LoanApplicationRegisterSource, replyTo: ActorRef) extends Command
  case class ValidateAggregate(ts: TransmittalSheet) extends Command
  case class CompleteMacroValidation(errors: LarValidationErrors, replyTo: ActorRef) extends Command
  case class VerifyEdits(editType: ValidationErrorType, verified: Boolean, replyTo: ActorRef) extends Command

  case class GetNamedErrorResultsPaginated(editName: String, page: Int) extends Command
  case object GetValidatedLines extends Command

  def props(supervisor: ActorRef, validationStats: ActorRef, id: SubmissionId): Props = Props(new HmdaFileValidator(supervisor, validationStats, id))

  def createHmdaFileValidator(system: ActorSystem, supervisor: ActorRef, validationStats: ActorRef, id: SubmissionId): ActorRef = {
    system.actorOf(HmdaFileValidator.props(supervisor, validationStats, id).withDispatcher("persistence-dispatcher"))
  }

  case class HmdaVerificationState(
      syntacticalEdits: Set[String] = Set(),
      validityEdits: Set[String] = Set(),
      qualityEdits: Set[String] = Set(),
      macroEdits: Set[String] = Set(),
      containsSVEdits: Boolean = false,
      containsQMEdits: Boolean = false,
      qualityVerified: Boolean = false,
      macroVerified: Boolean = false,
      ts: Option[TransmittalSheet] = None,
      larCount: Int = 0
  ) {
    def updated(event: Event): HmdaVerificationState = event match {

      case EditsVerified(editType, v) =>
        if (editType == Quality) this.copy(qualityVerified = v)
        else if (editType == Macro) this.copy(macroVerified = v)
        else this

      case TsSyntacticalError(e) =>
        this.copy(containsSVEdits = true, syntacticalEdits = syntacticalEdits + e.ruleName)
      case LarSyntacticalError(e) =>
        this.copy(containsSVEdits = true, syntacticalEdits = syntacticalEdits + e.ruleName)
      case TsValidityError(e) =>
        this.copy(containsSVEdits = true, validityEdits = validityEdits + e.ruleName)
      case LarValidityError(e) =>
        this.copy(containsSVEdits = true, validityEdits = validityEdits + e.ruleName)

      case TsQualityError(e) =>
        this.copy(containsQMEdits = true, qualityEdits = qualityEdits + e.ruleName)
      case LarQualityError(e) =>
        this.copy(containsQMEdits = true, qualityEdits = qualityEdits + e.ruleName)
      case LarMacroError(e) =>
        this.copy(containsQMEdits = true, macroEdits = macroEdits + e.ruleName)

      case LarValidated(_, _) => this.copy(larCount = larCount + 1)
      case TsValidated(tSheet) => this.copy(ts = Some(tSheet))
    }

    def bothVerified: Boolean = qualityVerified && macroVerified
    def readyToSign: Boolean = !containsSVEdits && (!containsQMEdits || bothVerified)
  }

  case class PaginatedErrors(errors: Seq[ValidationError], totalErrors: Int)
}

class HmdaFileValidator(supervisor: ActorRef, validationStats: ActorRef, submissionId: SubmissionId)
    extends HmdaPersistentActor with TsEngine with LarEngine with SourceUtils {

  import HmdaFileValidator._

  val config = ConfigFactory.load()
  val duration = config.getInt("hmda.actor-lookup-timeout")
  implicit val timeout = Timeout(duration.seconds)
  val parserPersistenceId = s"${HmdaFileParser.name}-$submissionId"

  var institution: Option[Institution] = Some(Institution.empty.copy(id = submissionId.institutionId))
  def ctx: ValidationContext = ValidationContext(institution, Try(Some(submissionId.period.toInt)).getOrElse(None))
  override def preStart(): Unit = {
    super.preStart()
    val fInstitutions = (supervisor ? FindActorByName(InstitutionPersistence.name)).mapTo[ActorRef]
    for {
      a <- fInstitutions
      i <- (a ? GetInstitutionById(submissionId.institutionId)).mapTo[Option[Institution]]
    } yield institution = i
  }

  var state = HmdaVerificationState()

  val fHmdaFiling = (supervisor ? FindHmdaFiling(submissionId.period)).mapTo[ActorRef]
  def statRef = for {
    manager <- (supervisor ? FindProcessingActor(SubmissionManager.name, submissionId)).mapTo[ActorRef]
    stat <- (manager ? GetActorRef(SubmissionLarStats.name)).mapTo[ActorRef]
  } yield stat

  override def updateState(event: Event): Unit = {
    state = state.updated(event)
  }

  override def persistenceId: String = s"$name-$submissionId"

  override def receiveCommand: Receive = {

    case BeginValidation(replyTo) =>
      sender() ! ValidationStarted(submissionId)
      events(parserPersistenceId)
        .filter(x => x.isInstanceOf[TsParsed])
        .map { e => e.asInstanceOf[TsParsed].ts }
        .map { ts =>
          self ! ts
          validationStats ! AddSubmissionTaxId(ts.taxId, submissionId)
          self ! ValidateAggregate(ts)
          validateTs(ts, ctx).toEither
        }
        .map {
          case Right(_) => // do nothing
          case Left(errors) => TsValidationErrors(errors.list.toList)
        }
        .runWith(Sink.actorRef(self, NotUsed))

      val larSource: Source[LoanApplicationRegister, NotUsed] = events(parserPersistenceId)
        .filter(x => x.isInstanceOf[LarParsed])
        .map(e => e.asInstanceOf[LarParsed].lar)

      larSource.map { lar =>
        self ! lar
        validateLar(lar, ctx).toEither
      }
        .map {
          case Right(_) => // do nothing
          case Left(errors) => LarValidationErrors(errors.list.toList)
        }
        .runWith(Sink.actorRef(self, ValidateMacro(larSource, replyTo)))

    case ValidateAggregate(ts) =>
      performAsyncChecks(ts, ctx)
        .map(validations => validations.toEither)
        .map {
          case Right(_) => // do nothing
          case Left(errors) => self ! TsValidationErrors(errors.list.toList)
        }

    case ts: TransmittalSheet =>
      persist(TsValidated(ts)) { e =>
        log.debug(s"Persisted: $e")
        updateState(e)
      }

    case lar: LoanApplicationRegister =>
      val validated = LarValidated(lar, submissionId)
      persist(validated) { e =>
        log.debug(s"Persisted: $e")
        updateState(e)
        for {
          f <- fHmdaFiling
          stat <- statRef
        } yield {
          f ! validated
          stat ! validated
        }
      }

    case ValidateMacro(larSource, replyTo) =>
      log.debug("Quality Validation completed")
      for {
        stat <- statRef
        _ <- (stat ? PersistStatsForMacroEdits).mapTo[MacroStatsUpdated]
        fMacro = checkMacro(larSource, ctx)
          .mapTo[LarSourceValidation]
          .map(larSourceValidation => larSourceValidation.toEither)
          .map {
            case Right(_) => CompleteValidation(replyTo)
            case Left(errors) => CompleteMacroValidation(LarValidationErrors(errors.list.toList), replyTo)
          }

      } yield {
        fMacro pipeTo self
      }

    case tsErrors: TsValidationErrors =>
      val errors = tsErrors.errors
      val syntacticalErrors = errorsOfType(errors, Syntactical)
        .map(e => TsSyntacticalError(e))
      persistErrors(syntacticalErrors)

      val validityErrors = errorsOfType(errors, Validity)
        .map(e => TsValidityError(e))
      persistErrors(validityErrors)

      val qualityErrors = errorsOfType(errors, Quality)
        .map(e => TsQualityError(e))
      persistErrors(qualityErrors)

    case larErrors: LarValidationErrors =>
      val errors = larErrors.errors
      val syntacticalErrors = errorsOfType(errors, Syntactical)
        .map(e => LarSyntacticalError(e))
      persistErrors(syntacticalErrors)

      val validityErrors = errorsOfType(errors, Validity)
        .map(e => LarValidityError(e))
      persistErrors(validityErrors)

      val qualityErrors = errorsOfType(errors, Quality)
        .map(e => LarQualityError(e))
      persistErrors(qualityErrors)

      val macroErrors = errorsOfType(errors, Macro)
        .map(e => LarMacroError(e))
      persistErrors(macroErrors)

    case CompleteMacroValidation(e, replyTo) =>
      self ! LarValidationErrors(e.errors)
      self ! CompleteValidation(replyTo)

    case CompleteValidation(replyTo, originalSender) =>
      if (state.readyToSign) {
        log.debug(s"Validation completed for $submissionId")
        replyTo ! ValidationCompleted(originalSender)
      } else {
        log.debug(s"Validation completed for $submissionId, errors found")
        replyTo ! ValidationCompletedWithErrors(originalSender)
      }

    case VerifyEdits(editType, v, replyTo) =>
      val client = sender()
      if (editType == Quality || editType == Macro) {
        persist(EditsVerified(editType, v)) { e =>
          updateState(e)
          self ! CompleteValidation(replyTo, Some(client))
        }
      } else client ! None

    case GetState =>
      sender() ! state

    case GetNamedErrorResultsPaginated(editName, page) =>
      val replyTo = sender()
      val allFailures = allEdits.filter(e => e.ruleName == editName)
      count(allFailures).map { total =>
        val p = PaginatedResource(total)(page)
        val pageOfFailuresF = allFailures.take(p.toIndex).drop(p.fromIndex).runWith(Sink.seq)
        pageOfFailuresF.map { pageOfFailures =>
          replyTo ! PaginatedErrors(pageOfFailures, total)
        }
      }

    case Shutdown =>
      context stop self

  }

  private def allEdits: Source[ValidationError, NotUsed] = {
    val edits = events(persistenceId).map {
      case LarSyntacticalError(err) => err
      case TsSyntacticalError(err) => err
      case LarValidityError(err) => err
      case TsValidityError(err) => err
      case LarQualityError(err) => err
      case TsQualityError(err) => err
      case LarMacroError(err) => err
      case _ => EmptyValidationError
    }
    edits.filter(_ != EmptyValidationError)
  }

  private def persistErrors(errors: Seq[Event]): Unit = {
    errors.foreach { error =>
      persist(error) { e =>
        log.debug(s"Persisted: $e")
        updateState(e)
      }
    }
  }

  private def errorsOfType(errors: Seq[ValidationError], errorType: ValidationErrorType): Seq[ValidationError] = {
    errors.filter(_.errorType == errorType)
  }
}
