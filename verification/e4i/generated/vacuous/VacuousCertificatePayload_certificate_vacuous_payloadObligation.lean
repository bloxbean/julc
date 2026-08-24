/- Generated exact-artifact compositional DSL obligation. -/
import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import GeneratedSchemas
import CheckedExecution
import SecurityProperty

namespace JulcGenerated.VacuousCertificatePayload_certificate_vacuous_payload

open CardanoLedgerApi.V3
open PlutusCore.UPLC.Utils
open JulcGenerated.UserProperty

#import_uplc validator PlutusV3 double_cbor_hex
  "artifacts/vacuous-certificate-payload.compiledCode.hex"
#prep_uplc appliedValidator validator certifyingInputs 5000

def composedObligation : Prop :=
  ∀ ctx : ScriptContext,
    selectedPurpose ctx = true →
    blasterValidCertifyingContext ctx = true →
    PlutusCore.UPLC.Utils.isSuccessful (appliedValidator.prop ctx) →
    dslProperty_certificate_vacuous_payload ctx

def hasNoSuccessfulInput : Prop :=
  ∀ ctx : ScriptContext,
    selectedPurpose ctx = true →
    blasterValidCertifyingContext ctx = true →
    ¬PlutusCore.UPLC.Utils.isSuccessful (appliedValidator.prop ctx)

end JulcGenerated.VacuousCertificatePayload_certificate_vacuous_payload
