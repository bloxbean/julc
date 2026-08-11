import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster

namespace JulcVerification.ControlledMint

open CardanoLedgerApi.V3
open PlutusCore.ByteString (ByteString)
open PlutusCore.Data (Data)
open PlutusCore.UPLC.Utils

set_option warn.sorry false

#import_uplc controlledMintValidator PlutusV3 double_cbor_hex
  "artifacts/controlled-mint.compiledCode.hex"

#prep_uplc appliedControlledMintValidator controlledMintValidator mintingInputs 30000

/-- The fixed 28-byte authority compiled into the fixture artifact. -/
def authority : ByteString := "JULC_VERIFY_AUTHORITY_000001"

/-- Exact authorization and singleton-map shape enforced by the artifact. -/
def expectedControlledMintShape (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo,
      ctx.scriptContextRedeemer,
      ctx.scriptContextTxInfo.txInfoSignatories,
      ctx.scriptContextTxInfo.txInfoMint with
  | .MintingScript _,
      Data.Constr _ (Data.B tokenName :: Data.I quantity :: _),
      firstSigner :: _,
      [(_, Data.Map [(Data.B actualToken, Data.I actualQuantity)])] =>
        firstSigner = authority ∧
        quantity = 1 ∧
        actualToken = tokenName ∧
        actualQuantity = quantity
  | _, _, _, _ => False

/-- Successful exact-artifact execution implies the controlled-mint rules. -/
theorem successfulImpliesExpectedControlledMint :
    ∀ ctx : ScriptContext,
      PlutusCore.UPLC.Utils.isSuccessful
          (appliedControlledMintValidator.prop ctx) →
        expectedControlledMintShape ctx := by
  blaster

/-- Non-vacuity control: the no-success claim must be solver-refuted. -/
def hasNoSuccessfulControlledMintInput : Prop :=
  ∀ ctx : ScriptContext,
    ¬PlutusCore.UPLC.Utils.isSuccessful
      (appliedControlledMintValidator.prop ctx)

#blaster (gen-cex: 1) (solve-result: 1)
  [hasNoSuccessfulControlledMintInput]

/--
Kernel-checked ledger composition: if a singleton mint contains the currency
symbol for which the ledger invoked the policy, that singleton's symbol is the
policy's own symbol.
-/
theorem singletonCurrencyMembershipImpliesOwnPolicy
    (ownPolicy actualPolicy tokenName : ByteString)
    (quantity : Int)
    (h : CardanoLedgerApi.V2.hasCurrencySymbol ownPolicy
      (CardanoLedgerApi.V2.singleton actualPolicy tokenName quantity) = true) :
    ownPolicy = actualPolicy := by
  simpa [CardanoLedgerApi.V2.hasCurrencySymbol,
    CardanoLedgerApi.V2.singleton] using h

end JulcVerification.ControlledMint
