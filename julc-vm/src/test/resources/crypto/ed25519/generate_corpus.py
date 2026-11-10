"""Ed25519 differential corpus with libsodium's verdicts (PyNaCl's bundled libsodium, crypto_sign_open).

Usage: python -I generate_corpus.py OUT_FILE SEED SCALE [MAX_MESSAGE_BYTES]
Line format: category pk_hex msg_hex sig_hex verdict(0/1)
The point arithmetic here only builds inputs; every verdict comes from libsodium.
"""
import hashlib
import random
import sys

from nacl._sodium import ffi, lib
import nacl.bindings as nb

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, P - 2, P)) % P
SQRT_M1 = pow(2, (P - 1) // 4, P)


def inv(x):
    return pow(x, P - 2, P)


def add(p, q):
    x1, y1 = p
    x2, y2 = q
    t = D * x1 * x2 * y1 * y2 % P
    x3 = (x1 * y2 + x2 * y1) * inv(1 + t) % P
    y3 = (y1 * y2 + x1 * x2) * inv(1 - t) % P
    return (x3, y3)


def neg(p):
    return ((-p[0]) % P, p[1])


IDENT = (0, 1)


def mul(k, p):
    r = IDENT
    q = p
    while k > 0:
        if k & 1:
            r = add(r, q)
        q = add(q, q)
        k >>= 1
    return r


def recover_x(y, sign):
    if y >= P:
        return None
    x2 = (y * y - 1) * inv(D * y * y + 1) % P
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P != 0:
        x = x * SQRT_M1 % P
    if (x * x - x2) % P != 0:
        return None
    if (x & 1) != sign:
        x = P - x
    return x


def decode(b):
    v = int.from_bytes(b, 'little')
    y = v & ((1 << 255) - 1)
    x = recover_x(y, v >> 255)
    return None if x is None else (x, y)


def encode(p):
    x, y = p
    return (y | ((x & 1) << 255)).to_bytes(32, 'little')


def enc_y(y, sign):
    return (y | (sign << 255)).to_bytes(32, 'little')


BY = 4 * inv(5) % P
B = (recover_x(BY, 0), BY)
T8 = decode(bytes.fromhex('26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05'))
TORSION = [mul(i, T8) for i in range(8)]
assert mul(8, T8) == IDENT and TORSION[4] != IDENT


def base_mul(s):
    s %= L
    if s == 0:
        return IDENT
    return decode(nb.crypto_scalarmult_ed25519_base_noclamp(s.to_bytes(32, 'little')))


def h_scalar(r_bytes, a_bytes, msg):
    return int.from_bytes(hashlib.sha512(r_bytes + a_bytes + msg).digest(), 'little') % L


def verdict(pk, msg, sig):
    sm = sig + msg
    m = ffi.new("unsigned char[]", len(sm))
    mlen = ffi.new("unsigned long long *")
    rc = lib.crypto_sign_open(m, mlen, sm, len(sm), pk)
    return 1 if rc == 0 else 0


def main():
    out_path, seed, scale = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    max_msg = int(sys.argv[4]) if len(sys.argv) > 4 else 600
    rnd = random.Random(seed)
    rows = []
    counts = {}

    def emit(cat, pk, msg, sig):
        assert len(pk) == 32 and len(sig) == 64
        v = verdict(pk, msg, sig)
        rows.append(f"{cat} {pk.hex()} {msg.hex()} {sig.hex()} {v}")
        c = counts.setdefault(cat, [0, 0])
        c[v] += 1

    def rbytes(n):
        return bytes(rnd.getrandbits(8) for _ in range(n))

    def rmsg():
        n = rnd.choice([0, 1, 2, 7, 32, 33, 64, 100, 255, rnd.randrange(0, 600)])
        return rbytes(min(n, max_msg))

    def keypair():
        seed_ = rbytes(32)
        pk, sk = nb.crypto_sign_seed_keypair(seed_)
        return pk, sk

    def sign(sk, msg):
        return nb.crypto_sign(msg, sk)[:64]

    # Raw secret scalar a with A = aB (+ torsion) and nonce r, signing by hand.
    def hand_sign(a, a_bytes, r_point_bytes, r, msg):
        k = h_scalar(r_point_bytes, a_bytes, msg)
        return ((r + k * a) % L).to_bytes(32, 'little'), k

    n_valid = 30 * scale
    valid = []
    for _ in range(n_valid):
        pk, sk = keypair()
        msg = rmsg()
        sig = sign(sk, msg)
        emit('valid', pk, msg, sig)
        valid.append((pk, msg, sig))

    # Bit flips in R, S, A, message.
    for i in range(20 * scale):
        pk, msg, sig = valid[i % len(valid)]
        where = rnd.choice(['R', 'S', 'A', 'M'])
        if where == 'M' and not msg:
            where = 'S'
        pk2, msg2, sig2 = bytearray(pk), bytearray(msg), bytearray(sig)
        if where == 'R':
            b = rnd.randrange(256); sig2[b // 8] ^= 1 << (b % 8)
        elif where == 'S':
            b = rnd.randrange(256); sig2[32 + b // 8] ^= 1 << (b % 8)
        elif where == 'A':
            b = rnd.randrange(256); pk2[b // 8] ^= 1 << (b % 8)
        else:
            b = rnd.randrange(len(msg) * 8); msg2[b // 8] ^= 1 << (b % 8)
        emit('flip' + where, bytes(pk2), bytes(msg2), bytes(sig2))

    # Sign-bit flips of R and A.
    for i in range(2 * scale):
        pk, msg, sig = valid[i % len(valid)]
        sig2 = bytearray(sig); sig2[31] ^= 0x80
        emit('signbitR', pk, msg, bytes(sig2))
        pk2 = bytearray(pk); pk2[31] ^= 0x80
        emit('signbitA', bytes(pk2), msg, sig)

    # S out of range.
    for i in range(3 * scale):
        pk, msg, sig = valid[i % len(valid)]
        s = int.from_bytes(sig[32:], 'little')
        variants = [s + L, s + 2 * L, L, L + 1, L - 1, s | (1 << 253),
                    2**256 - 1, s + rnd.randrange(1, 16) * L, rnd.randrange(L, 2**256)]
        for v in variants:
            if v < 2**256:
                emit('Srange', pk, msg, sig[:32] + v.to_bytes(32, 'little'))

    # Zero S.
    for i in range(scale):
        pk, msg, sig = valid[i % len(valid)]
        emit('zeroS', pk, msg, sig[:32] + bytes(32))
        emit('zeroS', pk, msg, encode(IDENT) + bytes(32))

    # Encodings of interest: every torsion point with both sign bits, its non-canonical form y + p where one
    # exists, and every y >= p, with both sign bits.
    special = []
    for t in TORSION:
        for sbit in (0, 1):
            special.append(('torsion', enc_y(t[1], sbit)))
            if t[1] + P < 2**255:
                special.append(('torsionNonCanon', enc_y(t[1] + P, sbit)))
    for y in range(P, 2**255):
        for sbit in (0, 1):
            special.append(('yGeP', enc_y(y, sbit)))
    special = list(dict.fromkeys(special))

    for i in range(2 * scale):
        pk, msg, sig = valid[i % len(valid)]
        for tag, e in special:
            emit('A-' + tag, e, msg, sig)
            emit('R-' + tag, pk, msg, e + sig[32:])
            # The same encoding as both key and R, with S = 0.
            if i % 8 == 0:
                emit('AR-' + tag, e, msg, e + bytes(32))

    # Every small-order key with every small-order R, S = 0 (as in speccheck) and a random S.
    for i in range(max(1, scale // 4)):
        msg = rmsg()
        for ta in TORSION:
            for tr in TORSION:
                emit('smallAR', encode(ta), msg, encode(tr) + bytes(32))
                emit('smallAR', encode(ta), msg, encode(tr) + (rnd.randrange(L)).to_bytes(32, 'little'))

    # Mixed-order keys and nonces: A = aB + TA, R = rB + TR, S = r + k a.
    for i in range(40 * scale):
        msg = rmsg()
        a = rnd.randrange(1, L)
        ta = TORSION[rnd.randrange(8)] if rnd.random() < 0.8 else IDENT
        A = add(base_mul(a), ta)
        a_bytes = encode(A)
        mode = rnd.choice(['randomTR', 'matchTR', 'matchTR', 'cofactoredOnly'])
        r = rnd.randrange(1, L)
        RB = base_mul(r)
        if mode == 'matchTR':
            # Cofactorless-valid: R = SB - kA needs TR = -k TA; retry r until it holds.
            for _ in range(64):
                tr = TORSION[rnd.randrange(8)]
                R = add(RB, tr)
                k = h_scalar(encode(R), a_bytes, msg)
                if add(tr, mul(k % 8, ta)) == IDENT:
                    break
                r = rnd.randrange(1, L); RB = base_mul(r)
        elif mode == 'cofactoredOnly':
            tr = TORSION[rnd.randrange(1, 8)]
            R = add(RB, tr)
        else:
            tr = TORSION[rnd.randrange(8)]
            R = add(RB, tr)
        r_bytes = encode(R)
        s_bytes, k = hand_sign(a, a_bytes, r_bytes, r, msg)
        emit('mixed-' + mode + ('-Atf' if ta == IDENT else ''), a_bytes, msg, r_bytes + s_bytes)

    # Torsion-free key, R with every torsion part (cofactored-valid, cofactorless-invalid unless TR = 0).
    for i in range(8 * scale):
        msg = rmsg()
        a = rnd.randrange(1, L)
        A = base_mul(a)
        a_bytes = encode(A)
        r = rnd.randrange(1, L)
        tr = TORSION[i % 8]
        R = add(base_mul(r), tr)
        r_bytes = encode(R)
        s_bytes, _ = hand_sign(a, a_bytes, r_bytes, r, msg)
        emit('mixedR-%d' % (i % 8), a_bytes, msg, r_bytes + s_bytes)

    # Key with torsion, honest R (torsion-free): cofactorless valid only when k TA = 0.
    for i in range(8 * scale):
        msg = rmsg()
        a = rnd.randrange(1, L)
        ta = TORSION[i % 8]
        A = add(base_mul(a), ta)
        a_bytes = encode(A)
        r = rnd.randrange(1, L)
        R = base_mul(r)
        r_bytes = encode(R)
        s_bytes, _ = hand_sign(a, a_bytes, r_bytes, r, msg)
        emit('mixedA-%d' % (i % 8), a_bytes, msg, r_bytes + s_bytes)

    # Random bytes everywhere (mostly not points).
    for i in range(3 * scale):
        emit('random', rbytes(32), rmsg(), rbytes(64))
        pk, msg, sig = valid[i % len(valid)]
        emit('randomR', pk, msg, rbytes(32) + sig[32:])
        emit('randomA', rbytes(32), msg, sig)

    rnd.shuffle(rows)
    with open(out_path, 'w') as f:
        for r in rows:
            f.write(r + '\n')
    for cat in sorted(counts):
        print(cat, counts[cat][1], 'accepted', counts[cat][0], 'rejected', file=sys.stderr)
    print('total', len(rows), file=sys.stderr)


if __name__ == '__main__':
    main()
