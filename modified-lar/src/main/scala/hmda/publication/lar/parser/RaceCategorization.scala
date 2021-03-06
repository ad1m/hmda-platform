package hmda.publication.lar

import hmda.model.filing.lar.{LoanApplicationRegister, Race}
import hmda.model.filing.lar.enums._

object RaceCategorization {

  def assignRaceCategorization(lar: LoanApplicationRegister): String = {

    val race = lar.applicant.race
    val coRace = lar.coApplicant.race

    val raceFields =
      Array(race.race1, race.race2, race.race3, race.race4, race.race5)
    val coRaceFields = Array(coRace.race1,
                             coRace.race2,
                             coRace.race3,
                             coRace.race4,
                             coRace.race5)

    val asianEnums = Array(Asian,
                           AsianIndian,
                           Chinese,
                           Filipino,
                           Japanese,
                           Korean,
                           Vietnamese,
                           OtherAsian)
    val hawaiianIslanderEnums = Array(NativeHawaiianOrOtherPacificIslander,
                                      NativeHawaiian,
                                      GuamanianOrChamorro,
                                      Samoan,
                                      OtherPacificIslander)

    val isRaceTwoToFiveEmpty = checkRaceTwoToFiveEmpty(race)
    val isRaceThreeToFiveEmpty = RaceThreeToFiveEmpty(race)
    val isCoRaceTwoToFiveEmpty = checkRaceTwoToFiveEmpty(coRace)
    val isCoWhite = AnyApplicantWhite(coRace)

    if (race.race1 == EmptyRaceValue) {
      "Free Form Text Only"
    } else if (race.race1 == RaceInformationNotProvided ||
               race.race1 == RaceNotApplicable) {
      "Race Not Available"
    }

    // American Indian or Alaska Native
    else if (race.race1 == AmericanIndianOrAlaskaNative &&
             isRaceTwoToFiveEmpty &&
             !isCoWhite) {
      AmericanIndianOrAlaskaNative.description
    }

    // Asian
    else if (asianEnums.contains(race.race1) &&
             isRaceTwoToFiveEmpty &&
             !isCoWhite) {
      Asian.description
    }

    // Black or African American
    else if (race.race1 == BlackOrAfricanAmerican &&
             isRaceTwoToFiveEmpty &&
             !isCoWhite) {
      BlackOrAfricanAmerican.description
    }

    // Native Hawaiian or Pacific Islander
    else if (hawaiianIslanderEnums.contains(race.race1) &&
             isRaceTwoToFiveEmpty &&
             !isCoWhite) {
      NativeHawaiianOrOtherPacificIslander.description
    }

    // American Indian or Alaska Native Alt
    else if (race.race1 == AmericanIndianOrAlaskaNative &&
             race.race2 == White &&
             isRaceThreeToFiveEmpty &&
             !isCoWhite) {
      AmericanIndianOrAlaskaNative.description
    }

    // Asian Alt
    else if (asianEnums.contains(race.race1) &&
             race.race2 == White &&
             isRaceThreeToFiveEmpty &&
             !isCoWhite) {
      Asian.description
    }

    // Black or African American Alt
    else if (race.race1 == BlackOrAfricanAmerican &&
             race.race2 == White &&
             isRaceThreeToFiveEmpty &&
             !isCoWhite) {
      BlackOrAfricanAmerican.description
    }

    // Native Hawaiian or Pacific Islander Alt
    else if (race.race1 == NativeHawaiianOrOtherPacificIslander &&
             race.race2 == White &&
             isRaceThreeToFiveEmpty &&
             !isCoWhite) {
      NativeHawaiianOrOtherPacificIslander.description
    }

    // Asian Alt Two
    else if (raceFields.exists(asianEnums.contains) &&
             raceFields.contains(White) &&
             !raceFields.exists(hawaiianIslanderEnums.contains) &&
             !raceFields.contains(BlackOrAfricanAmerican) &&
             !raceFields.contains(AmericanIndianOrAlaskaNative) &&
             !isCoWhite) {
      Asian.description
    } else if (raceFields.exists(asianEnums.contains)) {
      Asian.description
    }

    // Native Hawaiian or Pacific Islander Alt Two
    else if (raceFields.exists(hawaiianIslanderEnums.contains) &&
             raceFields.contains(White) &&
             !raceFields.exists(asianEnums.contains) &&
             !raceFields.contains(BlackOrAfricanAmerican) &&
             !raceFields.contains(AmericanIndianOrAlaskaNative) &&
             !isCoWhite) {
      NativeHawaiianOrOtherPacificIslander.description
    } else if (moreThanOneMinority(raceFields,
                                   asianEnums,
                                   hawaiianIslanderEnums) &&
               !isCoWhite) {
      "2 or more minority races"
    } else if (FirstApplicantWhite(race,
                                   coRace,
                                   isRaceTwoToFiveEmpty,
                                   isCoRaceTwoToFiveEmpty)) {
      White.description
    } else if (moreThanOneMinority(raceFields,
                                   asianEnums,
                                   hawaiianIslanderEnums) &&
               isCoWhite) {
      "Joint"
    } else if (moreThanOneMinority(coRaceFields,
                                   asianEnums,
                                   hawaiianIslanderEnums) &&
               AnyApplicantWhite(race)) {
      "Joint"
    } else
      "Race Not Available"
  }

  private def moreThanOneMinority(
      raceFields: Array[RaceEnum],
      asianEnums: Array[RaceEnum with Product with Serializable],
      hawaiianIslanderEnums: Array[RaceEnum with Product with Serializable])
    : Boolean = {
    var counter = 0

    if (raceFields.exists(hawaiianIslanderEnums.contains)) {
      counter += 1
    }

    if (raceFields.exists(asianEnums.contains)) {
      counter += 1
    }

    if (raceFields.contains(BlackOrAfricanAmerican)) {
      counter += 1
    }

    if (raceFields.contains(AmericanIndianOrAlaskaNative)) {
      counter += 1
    }

    if (counter > 1) {
      return true
    }

    false
  }

  private def AnyApplicantWhite(race: Race): Boolean = {
    (race.race1 == White ||
    race.race2 == White ||
    race.race3 == White ||
    race.race5 == White)
  }

  private def FirstApplicantWhite(race: Race,
                                  coRace: Race,
                                  isRaceTwoToFiveEmpty: Boolean,
                                  isCoRaceTwoToFiveEmpty: Boolean): Boolean = {
    race.race1 == White &&
    isRaceTwoToFiveEmpty &&
    isCoRaceTwoToFiveEmpty && (
      coRace.race1 == White ||
      coRace.race1 == RaceInformationNotProvided ||
      coRace.race1 == RaceNotApplicable ||
      coRace.race1 == RaceNoCoApplicant
    )
  }

  private def RaceThreeToFiveEmpty(race: Race): Boolean = {
    race.race3 == EmptyRaceValue &&
    race.race4 == EmptyRaceValue &&
    race.race5 == EmptyRaceValue
  }

  private def checkRaceTwoToFiveEmpty(race: Race): Boolean = {
    race.race2 == EmptyRaceValue &&
    race.race3 == EmptyRaceValue &&
    race.race4 == EmptyRaceValue &&
    race.race5 == EmptyRaceValue
  }
}
