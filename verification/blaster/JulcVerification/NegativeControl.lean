import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster

namespace JulcVerification.NegativeControl

open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.UPLC.Utils

set_option warn.sorry false

#import_uplc brokenMultiSigValidator PlutusV3 double_cbor_hex
  "artifacts/typed-multisig-broken.compiledCode.hex"

#prep_uplc appliedBrokenMultiSigValidator brokenMultiSigValidator spendingInputs 20000

def bothDatumKeysSigned (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo with
  | .SpendingScript _
      (some (Data.Constr 0 [Data.B firstKey, Data.B secondKey])) =>
      txSignedBy firstKey ctx.scriptContextTxInfo ∧
        txSignedBy secondKey ctx.scriptContextTxInfo
  | _ => False

def hasTwoKeyDatum (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo with
  | .SpendingScript _
      (some (Data.Constr 0 [Data.B _, Data.B _])) => True
  | _ => False

/-- The broken source ignores key2, so this claim must be refuted. -/
def brokenSuccessImpliesBothSigners : Prop :=
  ∀ ctx : ScriptContext,
    hasTwoKeyDatum ctx →
    PlutusCore.UPLC.Utils.isSuccessful
      (appliedBrokenMultiSigValidator.prop ctx) →
    bothDatumKeysSigned ctx

#blaster (gen-cex: 1) (solve-result: 1) [brokenSuccessImpliesBothSigners]

end JulcVerification.NegativeControl
