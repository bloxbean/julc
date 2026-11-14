"""RFC 8032 section 7.1 and Wycheproof ed25519_test.json with libsodium's verdicts.

Usage: python -I generate_fixed.py RFC8032_TXT WYCHEPROOF_JSON OUT_FILE
Line format: category pk_hex msg_hex sig_hex verdict, verdict 1/0 from libsodium's crypto_sign_open,
or E when a length is not 32/64 bytes (the Plutus builtin fails there).
"""
import json
import re
import sys

from nacl._sodium import ffi, lib


def verdict(pk, msg, sig):
    if len(pk) != 32 or len(sig) != 64:
        return 'E'
    sm = sig + msg
    m = ffi.new("unsigned char[]", len(sm))
    mlen = ffi.new("unsigned long long *")
    return '1' if lib.crypto_sign_open(m, mlen, sm, len(sm), pk) == 0 else '0'


def rfc_vectors(text):
    start = text.index('7.1.  Test Vectors for Ed25519\n')
    end = text.index('7.2.  Test Vectors for Ed25519ctx\n', start)
    section = text[start:end]
    section = re.sub(r'\n\nJosefsson.*?\n\f\nRFC 8032.*?\n', '\n', section, flags=re.S)
    out = []
    for block in section.split('-----TEST ')[1:]:
        name = block.split('\n', 1)[0].strip()

        def field(label, nxt):
            m = re.search(label + r':\s*\n(.*?)\n\s*' + nxt, block, flags=re.S)
            return re.sub(r'[^0-9a-f]', '', m.group(1)) if m else ''
        pk = field('PUBLIC KEY', 'MESSAGE')
        msg = field(r'MESSAGE \(length \d+ bytes?\)', 'SIGNATURE')
        sig = re.sub(r'[^0-9a-f]', '', re.search(r'SIGNATURE:\s*\n(.*?)(\n\n|$)', block, flags=re.S).group(1))
        out.append(('rfc8032-' + name.replace(' ', ''), pk, msg, sig))
    return out


def main():
    rfc = rfc_vectors(open(sys.argv[1]).read())
    wy = json.load(open(sys.argv[2]))
    rows, differ = [], []
    for cat, pk, msg, sig in rfc:
        v = verdict(bytes.fromhex(pk), bytes.fromhex(msg), bytes.fromhex(sig))
        assert v == '1', cat
        rows.append(f"{cat} {pk} {msg} {sig} {v}")
    for g in wy['testGroups']:
        pk = g['publicKey']['pk']
        for t in g['tests']:
            v = verdict(bytes.fromhex(pk), bytes.fromhex(t['msg']), bytes.fromhex(t['sig']))
            wv = {'valid': '1', 'invalid': '0'}.get(t['result'], '?')
            if v != 'E' and v != wv:
                differ.append((t['tcId'], t['result'], v, t['comment'], t['flags']))
            rows.append(f"wycheproof-{t['tcId']} {pk} {t['msg']} {t['sig']} {v}")
    with open(sys.argv[3], 'w') as f:
        for r in rows:
            f.write(r + '\n')
    print('rfc8032', len(rfc), 'wycheproof', sum(len(g['tests']) for g in wy['testGroups']), file=sys.stderr)
    print('wycheproof verdict != libsodium:', differ, file=sys.stderr)
    print('length errors:', sum(1 for r in rows if r.endswith(' E')), file=sys.stderr)


if __name__ == '__main__':
    main()
