# ADR-034 compiled-source List-case measurements

Generated with `./gradlew :julc-benchmark:listCaseEvidence`.
Baseline is the historical `BASELINE` optimization level; the candidate is
`PV11_SAFE` and includes existing O2 rules. For isolated O3 comparisons against
the previous PV11_SAFE compiler, see the historical-byte regression fixtures
and the accompanying validation report.

### o3-for-each-sum

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `ffc94b0c777d46361fa2646f5106e5318e7d0f56ea3e36a1e837d3e2`; candidate script hash: `a526d59af2bd6d1f0d47ae00407e95fd6eb8d59a93aaf2d7e0e00a20`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 74 | 70 | -4 |
| UPLC term nodes | 82 | 74 | -8 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| java | singleton | SUCCESS | 2101762 | 1528851 | -572911 | 9596 | 7930 | -1666 |
| java | many | SUCCESS | 4416256 | 3073621 | -1342635 | 18858 | 15262 | -3596 |
| java | break | SUCCESS | 4416256 | 3073621 | -1342635 | 18858 | 15262 | -3596 |
| java | bad-first | FAILURE | 1498072 | 1113210 | -384862 | 7361 | 6396 | -965 |
| java | bad-later | FAILURE | 2655319 | 1885595 | -769724 | 11992 | 10062 | -1930 |
| java | break-before-bad | FAILURE | 2655319 | 1885595 | -769724 | 11992 | 10062 | -1930 |
| java | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |
| truffle | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| truffle | singleton | SUCCESS | 2101762 | 1528851 | -572911 | 9596 | 7930 | -1666 |
| truffle | many | SUCCESS | 4416256 | 3073621 | -1342635 | 18858 | 15262 | -3596 |
| truffle | break | SUCCESS | 4416256 | 3073621 | -1342635 | 18858 | 15262 | -3596 |
| truffle | bad-first | FAILURE | 1498072 | 1113210 | -384862 | 7361 | 6396 | -965 |
| truffle | bad-later | FAILURE | 2655319 | 1885595 | -769724 | 11992 | 10062 | -1930 |
| truffle | break-before-bad | FAILURE | 2655319 | 1885595 | -769724 | 11992 | 10062 | -1930 |
| truffle | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-traced

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `5dc756bec1fdf9781a5185dfcce8e4388d42e112aa8c2d02ddd134c4`; candidate script hash: `b2013c38a05e6f5dd51a7aa57d89573998334ca9ba1eef0127c25228`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 118 | 113 | -5 |
| UPLC term nodes | 108 | 100 | -8 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 1371554 | 1183505 | -188049 | 6793 | 6092 | -701 |
| java | singleton | SUCCESS | 2768342 | 2195431 | -572911 | 12420 | 10754 | -1666 |
| java | many | SUCCESS | 5561918 | 4219283 | -1342635 | 23674 | 20078 | -3596 |
| java | break | SUCCESS | 5561918 | 4219283 | -1342635 | 23674 | 20078 | -3596 |
| java | bad-first | FAILURE | 1925111 | 1540249 | -384862 | 9189 | 8224 | -965 |
| java | bad-later | FAILURE | 3321899 | 2552175 | -769724 | 14816 | 12886 | -1930 |
| java | break-before-bad | FAILURE | 3321899 | 2552175 | -769724 | 14816 | 12886 | -1930 |
| java | bad-outer | FAILURE | 389531 | 389531 | 0 | 2064 | 2064 | 0 |
| truffle | empty | SUCCESS | 1371554 | 1183505 | -188049 | 6793 | 6092 | -701 |
| truffle | singleton | SUCCESS | 2768342 | 2195431 | -572911 | 12420 | 10754 | -1666 |
| truffle | many | SUCCESS | 5561918 | 4219283 | -1342635 | 23674 | 20078 | -3596 |
| truffle | break | SUCCESS | 5561918 | 4219283 | -1342635 | 23674 | 20078 | -3596 |
| truffle | bad-first | FAILURE | 1925111 | 1540249 | -384862 | 9189 | 8224 | -965 |
| truffle | bad-later | FAILURE | 3321899 | 2552175 | -769724 | 14816 | 12886 | -1930 |
| truffle | break-before-bad | FAILURE | 3321899 | 2552175 | -769724 | 14816 | 12886 | -1930 |
| truffle | bad-outer | FAILURE | 389531 | 389531 | 0 | 2064 | 2064 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-stop

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `15b1d74c6343dc8232925c0e74e1fe4df86acc0018fe41cb535d93f6`; candidate script hash: `ecacd1f4d9239fc7ade71a74ef38761634482f8442550704772f1014`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 102 | 93 | -9 |
| UPLC term nodes | 105 | 90 | -15 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| java | singleton | SUCCESS | 2677685 | 1916725 | -760960 | 11894 | 9527 | -2367 |
| java | many | SUCCESS | 6144025 | 4237243 | -1906782 | 25752 | 20053 | -5699 |
| java | break | SUCCESS | 3698710 | 2682551 | -1016159 | 15758 | 12758 | -3000 |
| java | bad-first | FAILURE | 1144409 | 889210 | -255199 | 5629 | 4996 | -633 |
| java | bad-later | FAILURE | 2877579 | 2049469 | -828110 | 12558 | 10259 | -2299 |
| java | break-before-bad | SUCCESS | 1965540 | 1522292 | -443248 | 8829 | 7495 | -1334 |
| java | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |
| truffle | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| truffle | singleton | SUCCESS | 2677685 | 1916725 | -760960 | 11894 | 9527 | -2367 |
| truffle | many | SUCCESS | 6144025 | 4237243 | -1906782 | 25752 | 20053 | -5699 |
| truffle | break | SUCCESS | 3698710 | 2682551 | -1016159 | 15758 | 12758 | -3000 |
| truffle | bad-first | FAILURE | 1144409 | 889210 | -255199 | 5629 | 4996 | -633 |
| truffle | bad-later | FAILURE | 2877579 | 2049469 | -828110 | 12558 | 10259 | -2299 |
| truffle | break-before-bad | SUCCESS | 1965540 | 1522292 | -443248 | 8829 | 7495 | -1334 |
| truffle | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-multi

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `5c61fc73c4936789188833dc7b492818c591503851bb4af45113f3c5`; candidate script hash: `51329400d7c4175c68bcafad780e9b6c8cb94ad1825ab5dfa8fdd478`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 180 | 171 | -9 |
| UPLC term nodes | 200 | 185 | -15 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 2296692 | 2108643 | -188049 | 9589 | 8888 | -701 |
| java | singleton | SUCCESS | 5083545 | 4322585 | -760960 | 20344 | 17977 | -2367 |
| java | many | SUCCESS | 10657251 | 8750469 | -1906782 | 41854 | 36155 | -5699 |
| java | break | SUCCESS | 7158253 | 6142094 | -1016159 | 28034 | 25034 | -3000 |
| java | bad-first | FAILURE | 1582974 | 1327775 | -255199 | 7389 | 6756 | -633 |
| java | bad-later | FAILURE | 4369827 | 3541717 | -828110 | 18144 | 15845 | -2299 |
| java | break-before-bad | SUCCESS | 4371400 | 3928152 | -443248 | 17279 | 15945 | -1334 |
| java | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |
| truffle | empty | SUCCESS | 2296692 | 2108643 | -188049 | 9589 | 8888 | -701 |
| truffle | singleton | SUCCESS | 5083545 | 4322585 | -760960 | 20344 | 17977 | -2367 |
| truffle | many | SUCCESS | 10657251 | 8750469 | -1906782 | 41854 | 36155 | -5699 |
| truffle | break | SUCCESS | 7158253 | 6142094 | -1016159 | 28034 | 25034 | -3000 |
| truffle | bad-first | FAILURE | 1582974 | 1327775 | -255199 | 7389 | 6756 | -633 |
| truffle | bad-later | FAILURE | 4369827 | 3541717 | -828110 | 18144 | 15845 | -2299 |
| truffle | break-before-bad | SUCCESS | 4371400 | 3928152 | -443248 | 17279 | 15945 | -1334 |
| truffle | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-failSelected

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `cac309287204ea2709bc02732cba57c934503e4fc1eb5e350a67909d`; candidate script hash: `75cadac3fdef521490c6e9df7d5855f7d71e225b3752c0e062a7b46c`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 101 | 92 | -9 |
| UPLC term nodes | 106 | 91 | -15 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| java | singleton | SUCCESS | 2512477 | 1751517 | -760960 | 11492 | 9125 | -2367 |
| java | many | SUCCESS | 5648401 | 3741619 | -1906782 | 24546 | 18847 | -5699 |
| java | break | FAILURE | 3753957 | 2608135 | -1145822 | 16886 | 13554 | -3332 |
| java | bad-first | FAILURE | 1498072 | 1113210 | -384862 | 7361 | 6396 | -965 |
| java | bad-later | FAILURE | 3066034 | 2108261 | -957773 | 13888 | 11257 | -2631 |
| java | break-before-bad | FAILURE | 2185995 | 1613084 | -572911 | 10359 | 8693 | -1666 |
| java | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |
| truffle | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| truffle | singleton | SUCCESS | 2512477 | 1751517 | -760960 | 11492 | 9125 | -2367 |
| truffle | many | SUCCESS | 5648401 | 3741619 | -1906782 | 24546 | 18847 | -5699 |
| truffle | break | FAILURE | 3753957 | 2608135 | -1145822 | 16886 | 13554 | -3332 |
| truffle | bad-first | FAILURE | 1498072 | 1113210 | -384862 | 7361 | 6396 | -965 |
| truffle | bad-later | FAILURE | 3066034 | 2108261 | -957773 | 13888 | 11257 | -2631 |
| truffle | break-before-bad | FAILURE | 2185995 | 1613084 | -572911 | 10359 | 8693 | -1666 |
| truffle | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-unchecked

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `2dd96a5b7e52b6d5006440d7435d3ef7817c7760255e08e944bb0aa6`; candidate script hash: `faa3f1c0e9f8309939f8f2bf0649ab13cf6d4029b0051abb83a1c51d`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 70 | 65 | -5 |
| UPLC term nodes | 77 | 69 | -8 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | singleton | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | many | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | break | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | bad-first | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | bad-later | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | break-before-bad | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| java | bad-outer | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | empty | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | singleton | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | many | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | break | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | bad-first | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | bad-later | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | break-before-bad | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |
| truffle | bad-outer | FAILURE | 698533 | 618533 | -80000 | 4032 | 3532 | -500 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-effects

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `6d3359070c0277b8267a0afb47c44044d66b23749fd3ef1ab164ebbd`; candidate script hash: `8e19cdb9640cc2e9184b5ea89d5bfbba962d90b95aa224bc2b49c30d`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 83 | 78 | -5 |
| UPLC term nodes | 85 | 77 | -8 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| java | singleton | SUCCESS | 2123351 | 1550440 | -572911 | 9958 | 8292 | -1666 |
| java | many | SUCCESS | 4481023 | 3138388 | -1342635 | 19944 | 16348 | -3596 |
| java | break | SUCCESS | 4481023 | 3138388 | -1342635 | 19944 | 16348 | -3596 |
| java | bad-first | FAILURE | 1498072 | 1113210 | -384862 | 7361 | 6396 | -965 |
| java | bad-later | FAILURE | 2676908 | 1907184 | -769724 | 12354 | 10424 | -1930 |
| java | break-before-bad | FAILURE | 2676908 | 1907184 | -769724 | 12354 | 10424 | -1930 |
| java | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |
| truffle | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| truffle | singleton | SUCCESS | 2123351 | 1550440 | -572911 | 9958 | 8292 | -1666 |
| truffle | many | SUCCESS | 4481023 | 3138388 | -1342635 | 19944 | 16348 | -3596 |
| truffle | break | SUCCESS | 4481023 | 3138388 | -1342635 | 19944 | 16348 | -3596 |
| truffle | bad-first | FAILURE | 1498072 | 1113210 | -384862 | 7361 | 6396 | -965 |
| truffle | bad-later | FAILURE | 2676908 | 1907184 | -769724 | 12354 | 10424 | -1930 |
| truffle | break-before-bad | FAILURE | 2676908 | 1907184 | -769724 | 12354 | 10424 | -1930 |
| truffle | bad-outer | FAILURE | 202033 | 202033 | 0 | 1232 | 1232 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`.

### o3-for-each-nested

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `e9620d744e1cc4db36dd6ebda4b8f320f564d03afcab28b21ab9b243`; candidate script hash: `dcc08603f3ddb8dd624356c509130c0b0713457d161a4031b5700e8e`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 127 | 117 | -10 |
| UPLC term nodes | 144 | 128 | -16 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| java | nested | SUCCESS | 9299386 | 6238018 | -3061368 | 40644 | 32050 | -8594 |
| java | bad-row | FAILURE | 1503261 | 1118399 | -384862 | 7361 | 6396 | -965 |
| java | bad-element | FAILURE | 3908547 | 2753961 | -1154586 | 17821 | 14926 | -2895 |
| truffle | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| truffle | nested | SUCCESS | 9299386 | 6238018 | -3061368 | 40644 | 32050 | -8594 |
| truffle | bad-row | FAILURE | 1503261 | 1118399 | -384862 | 7361 | 6396 | -965 |
| truffle | bad-element | FAILURE | 3908547 | 2753961 | -1154586 | 17821 | 14926 | -2895 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-recursive

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `e52a85650b8fea895a378455750f1aca82559536cafe1680dac07747`; candidate script hash: `c6cae7528e8104435fa66e23ad66d5ba3520961a3e04a297e618677b`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 127 | 118 | -9 |
| UPLC term nodes | 139 | 124 | -15 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | three | SUCCESS | 16080238 | 11300137 | -4780101 | 67462 | 53870 | -13592 |
| java | unselected | FAILURE | 362033 | 362033 | 0 | 2232 | 2232 | 0 |
| truffle | three | SUCCESS | 16080238 | 11300137 | -4780101 | 67462 | 53870 | -13592 |
| truffle | unselected | FAILURE | 362033 | 362033 | 0 | 2232 | 2232 | 0 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-mutualA

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `9f60ad693b88348cd491825d82f856f7152d6d203f8a8128b0f5e8ed`; candidate script hash: `f9c99287c8f8e633629a51c9492c11e9df1bdef46f69c75cefbd0bd2`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 347 | 320 | -27 |
| UPLC term nodes | 389 | 344 | -45 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | three | SUCCESS | 16272238 | 11492137 | -4780101 | 68662 | 55070 | -13592 |
| truffle | three | SUCCESS | 16272238 | 11492137 | -4780101 | 68662 | 55070 | -13592 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-records

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `417b45d9319271eef0638065b17ef9208b833792aef9478f9cd94608`; candidate script hash: `f900f84f18ee2e73989aa54c6e75199dc7933b11e0f218c2de02d07b`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 82 | 74 | -8 |
| UPLC term nodes | 91 | 80 | -11 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| java | record | SUCCESS | 2495492 | 1874581 | -620911 | 10592 | 8626 | -1966 |
| java | bad-record | FAILURE | 1757916 | 1325054 | -432862 | 8961 | 7696 | -1265 |
| java | bad-field | FAILURE | 2003802 | 1570940 | -432862 | 9057 | 7792 | -1265 |
| java | missing-field | FAILURE | 1983058 | 1550196 | -432862 | 9025 | 7760 | -1265 |
| truffle | empty | SUCCESS | 944515 | 756466 | -188049 | 4965 | 4264 | -701 |
| truffle | record | SUCCESS | 2495492 | 1874581 | -620911 | 10592 | 8626 | -1966 |
| truffle | bad-record | FAILURE | 1757916 | 1325054 | -432862 | 8961 | 7696 | -1265 |
| truffle | bad-field | FAILURE | 2003802 | 1570940 | -432862 | 9057 | 7792 | -1265 |
| truffle | missing-field | FAILURE | 1983058 | 1550196 | -432862 | 9025 | 7760 | -1265 |

Applied candidate rules: `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`.

### o3-for-each-aggregate

Target: `plutus-v3-pv11-uplc-1.1.0`; cost profile: `cardano-node-11.0.1-plutus-v3-pv11` (`40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8`).

Baseline script hash: `47af629d15d5c3c3367c64ccafdd5d1932e42bb2d1d7cbb4df7866ad`; candidate script hash: `ef97c08bd6faf6a623fa2e2e8f11e047a2827b29b2ae510366481ea9`.

| Metric | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FLAT bytes | 203 | 123 | -80 |
| UPLC term nodes | 224 | 125 | -99 |

| Backend | Case | Outcome | CPU baseline | CPU candidate | CPU delta | Memory baseline | Memory candidate | Memory delta |
|---|---|---|---:|---:|---:|---:|---:|---:|
| java | accept | SUCCESS | 7669884 | 3537513 | -4132371 | 29571 | 16869 | -12702 |
| java | reject | SUCCESS | 6180300 | 3223972 | -2956328 | 27266 | 15866 | -11400 |
| java | negative-drop | SUCCESS | 7621892 | 4309898 | -3311994 | 29433 | 20535 | -8898 |
| java | empty-after-drop | SUCCESS | 6276284 | 1683116 | -4593168 | 27542 | 8534 | -19008 |
| java | skip-malformed | SUCCESS | 6512637 | 2765128 | -3747509 | 24940 | 13203 | -11737 |
| java | visit-malformed | FAILURE | 3712685 | 1705366 | -2007319 | 17496 | 9164 | -8332 |
| truffle | accept | SUCCESS | 7669884 | 3537513 | -4132371 | 29571 | 16869 | -12702 |
| truffle | reject | SUCCESS | 6180300 | 3223972 | -2956328 | 27266 | 15866 | -11400 |
| truffle | negative-drop | SUCCESS | 7621892 | 4309898 | -3311994 | 29433 | 20535 | -8898 |
| truffle | empty-after-drop | SUCCESS | 6276284 | 1683116 | -4593168 | 27542 | 8534 | -19008 |
| truffle | skip-malformed | SUCCESS | 6512637 | 2765128 | -3747509 | 24940 | 13203 | -11737 |
| truffle | visit-malformed | FAILURE | 3712685 | 1705366 | -2007319 | 17496 | 9164 | -8332 |

Applied candidate rules: `pv11.o1.drop-list`, `pv11.o2.case-bool`, `pv11.o3.case-list`, `constant-fold`, `dead-code-elimination`, `beta-reduce`, `eta-reduce`, `pv11.o13.exp-mod-literal-fold`.
