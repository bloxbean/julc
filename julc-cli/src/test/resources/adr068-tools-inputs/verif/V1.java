package p;
import org.julclang.verification.annotation.*;
@RequiresSigner(/* who */ "datum.owner")
@ControlledMint(policy = "abcd", tokenName = "ab", amount = - 5)
class V1 {}
@RequiresSigner("datum." + "owner")
class V2 {}
@RequiresSigner(value = """
    datum.owner""")
class V3 {}
@ControlledMint(policy = "ab", tokenName = "cd", amount = 010)
class V4 {}
@ControlledMint(policy = "ab", tokenName = "cd", amount = 0x7fff_ffffL)
class V5 {}
@ControlledMint(policy = "ab", tokenName = "cd", amount = 1)
class V6 {}
	@RequiresSigner("datum.owner") class V7 {}
@org.julclang.verification.annotation.RequiresSigner(value =
    "datum.owner" )
class V8 {}
@Monotonic(current = "datum.count", next = "datum.count", relation = (Relation.GREATER_OR_EQUAL))
@PreservesValue
@RequiresSigner("datum.owner")
class V9 {}
