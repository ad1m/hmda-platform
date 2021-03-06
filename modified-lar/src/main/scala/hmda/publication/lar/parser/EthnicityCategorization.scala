package hmda.publication

import hmda.model.filing.lar.LoanApplicationRegister
import hmda.model.filing.lar.enums._

object EthnicityCategorization {

  def assignEthnicityCategorization(lar: LoanApplicationRegister): String = {
    val ethnicity = lar.applicant.ethnicity
    val coEthnicity = lar.coApplicant.ethnicity
    val ethnicityFields = Array(ethnicity.ethnicity1,
      ethnicity.ethnicity2,
      ethnicity.ethnicity3,
      ethnicity.ethnicity4,
      ethnicity.ethnicity5)

    val coethnicityFields = Array(coEthnicity.ethnicity1,
      coEthnicity.ethnicity2,
      coEthnicity.ethnicity3,
      coEthnicity.ethnicity4,
      coEthnicity.ethnicity5)

    val hispanicEnums = Array(HispanicOrLatino,
      Mexican,
      PuertoRican,
      Cuban,
      OtherHispanicOrLatino)

    val coapplicantNoHispanicEnums = (!hispanicEnums.contains(
      coEthnicity.ethnicity1) && !hispanicEnums.contains(coEthnicity.ethnicity2) && !hispanicEnums
      .contains(coEthnicity.ethnicity3) && !hispanicEnums.contains(
      coEthnicity.ethnicity4) && !hispanicEnums.contains(
      coEthnicity.ethnicity5))

    val applicantBlankExcept1 = ethnicity.ethnicity2 == EmptyEthnicityValue && ethnicity.ethnicity3 == EmptyEthnicityValue && ethnicity.ethnicity4 == EmptyEthnicityValue && ethnicity.ethnicity5 == EmptyEthnicityValue

    val applicantOnlyHispanic = ethnicityFields
      .map(hispanicEnums.contains(_))
      .reduce(_ || _)

    val coApplicantOnlyHispanic = coethnicityFields
      .map(hispanicEnums.contains(_))
      .reduce(_ || _)

    val applicantNotHispanic = ethnicityFields
      .map(_ == NotHispanicOrLatino)
      .reduce(_ && _)

    val coapplicantNotHispanic = ethnicityFields
      .map(_ != NotHispanicOrLatino)
      .reduce(_ && _)

    if (ethnicity.otherHispanicOrLatino != "" && ethnicity.ethnicity1 == EmptyEthnicityValue)
      "Free Form Text Only"
    else if (ethnicity.ethnicity1 == InformationNotProvided || ethnicity.ethnicity1 == EthnicityNotApplicable)
      "Ethnicity Not Available"
    else if (ethnicity.ethnicity1 == NotHispanicOrLatino && applicantBlankExcept1 && coapplicantNoHispanicEnums)
      "Not Hispanic or Latino"
    else if (hispanicEnums.contains(ethnicity.ethnicity1) && applicantBlankExcept1 && coapplicantNotHispanic)
      "Hispanic or Latino"
    else if (applicantOnlyHispanic && coapplicantNotHispanic)
      "Hispanic or Latino"
    else if (ethnicityFields.contains(hispanicEnums) || coethnicityFields.contains(hispanicEnums))
      "Hispanic or Latino"
    else if (applicantOnlyHispanic && coapplicantNotHispanic)
      "Joint"
    else if (coApplicantOnlyHispanic && applicantNotHispanic)
      "Joint"
    else if (ethnicityFields.contains(NotHispanicOrLatino) && applicantOnlyHispanic)
      "Joint"
    else if (coethnicityFields.contains(NotHispanicOrLatino) && coApplicantOnlyHispanic)
      "Joint"
    else
      "Ethnicity Not Available"
  }
}
