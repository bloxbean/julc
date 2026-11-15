// A hand-written Aiken equivalent of JOUST's Counter (Counter2.jsc), with the checks JOUST's generated validator
// makes: a one-shot instance token named from the seed, exactly one continuing output at the same address holding the
// token, the inline datum equal to the action's result, the exact value carried over, the caller's signature where
// the method reads caller, the datum size bound, and a destroy that burns the token and pays the balance to the owner.
use aiken/builtin
use aiken/cbor
use aiken/collection/dict
use aiken/collection/list
use aiken/crypto.{blake2b_256}
use cardano/address.{Script, VerificationKey}
use cardano/assets.{PolicyId}
use cardano/transaction.{InlineDatum, Input, Output, OutputReference, Transaction}

pub type State {
  count: Int,
  owner: ByteArray,
}

pub type MintAction {
  Create { seed: OutputReference, caller: ByteArray }
  Burn
}

pub type Action {
  Increment { by: Int }
  Reset { caller: ByteArray }
  Close { caller: ByteArray }
}

fn signed(tx: Transaction, key: ByteArray) -> Bool {
  list.has(tx.extra_signatories, key)
}

fn small(d: Data) -> Bool {
  builtin.length_of_bytearray(cbor.serialise(d)) <= 128
}

fn holds(o: Output, policy: PolicyId, name: ByteArray) -> Bool {
  assets.quantity_of(o.value, policy, name) == 1
}

fn continuing(tx: Transaction, own: Output, policy: PolicyId, name: ByteArray, next: State) -> Bool {
  expect [out] = list.filter(tx.outputs, fn(o) { holds(o, policy, name) })
  expect InlineDatum(d) = out.datum
  let next_data: Data = next
  and {
    out.address == own.address,
    out.value == own.value,
    d == next_data,
    small(d),
    assets.tokens(tx.mint, policy) == dict.empty,
  }
}

validator counter {
  mint(redeemer: MintAction, policy: PolicyId, tx: Transaction) {
    when redeemer is {
      Create { seed, caller } -> {
        let name = blake2b_256(cbor.serialise(seed))
        expect [Pair(minted, 1)] = dict.to_pairs(assets.tokens(tx.mint, policy))
        expect [out] = list.filter(tx.outputs, fn(o) { holds(o, policy, name) })
        expect InlineDatum(d) = out.datum
        let first: Data = State { count: 0, owner: caller }
        and {
          minted == name,
          list.any(tx.inputs, fn(i) { i.output_reference == seed }),
          out.address.payment_credential == Script(policy),
          d == first,
          small(d),
          signed(tx, caller),
        }
      }
      Burn -> {
        let burnt = dict.to_pairs(assets.tokens(tx.mint, policy))
        list.all(burnt, fn(p) { p.2nd == -1 })
      }
    }
  }

  spend(datum: Option<State>, redeemer: Action, own_ref: OutputReference, tx: Transaction) {
    expect Some(state) = datum
    expect Some(own) = transaction.find_input(tx.inputs, own_ref)
    expect Script(policy) = own.output.address.payment_credential
    expect [Pair(name, 1)] = dict.to_pairs(assets.tokens(own.output.value, policy))
    when redeemer is {
      Increment { by } ->
        by > 0 && continuing(tx, own.output, policy, name, State { ..state, count: state.count + by })
      Reset { caller } ->
        caller == state.owner && signed(tx, caller) && continuing(
          tx,
          own.output,
          policy,
          name,
          State { ..state, count: 0 },
        )
      Close { caller } -> {
        let balance = assets.lovelace_of(own.output.value)
        and {
          caller == state.owner,
          signed(tx, caller),
          assets.quantity_of(tx.mint, policy, name) == -1,
          list.any(
            tx.outputs,
            fn(o) {
              o.address.payment_credential == VerificationKey(state.owner) && assets.lovelace_of(o.value) >= balance
            },
          ),
        }
      }
    }
  }

  else(_) {
    fail
  }
}
