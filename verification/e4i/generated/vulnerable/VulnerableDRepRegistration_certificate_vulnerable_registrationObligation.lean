/- Generated exact-artifact compositional DSL obligation. -/
import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import GeneratedSchemas
import CheckedExecution
import SecurityProperty

namespace JulcGenerated.VulnerableDRepRegistration_certificate_vulnerable_registration

open CardanoLedgerApi.V3
open PlutusCore.UPLC.Utils
open JulcGenerated.UserProperty

#import_uplc validator PlutusV3 double_cbor_hex
  "artifacts/vulnerable-drep-registration.compiledCode.hex"
#prep_uplc appliedValidator validator certifyingInputs 5000

def composedObligation : Prop :=
  ∀ ctx : ScriptContext,
    selectedPurpose ctx = true →
    blasterValidCertifyingContext ctx = true →
    PlutusCore.UPLC.Utils.isSuccessful (appliedValidator.prop ctx) →
    dslProperty_certificate_vulnerable_registration ctx

def hasNoSuccessfulInput : Prop :=
  ∀ ctx : ScriptContext,
    selectedPurpose ctx = true →
    blasterValidCertifyingContext ctx = true →
    ¬PlutusCore.UPLC.Utils.isSuccessful (appliedValidator.prop ctx)

end JulcGenerated.VulnerableDRepRegistration_certificate_vulnerable_registration
