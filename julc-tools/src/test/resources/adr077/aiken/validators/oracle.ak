pub type Mode {
  Fast
  Slow { delay: Int }
}

pub type Config {
  owner: ByteArray,
  threshold: Int,
  tags: List<Int>,
  mode: Mode,
}

/// Three parameters: bytes, an integer and a record with a list and a sum type.
validator oracle(owner: ByteArray, deadline: Int, config: Config) {
  else(ctx: Data) {
    let n = deadline + config.threshold
    owner == config.owner && n > 0 && ctx != ctx
  }
}

/// One opaque Data parameter, and one optional integer.
validator anydata(anything: Data, limit: Option<Int>) {
  else(_ctx: Data) {
    when limit is {
      Some(l) -> l > 0 && anything == anything
      None -> False
    }
  }
}
