{-# LANGUAGE LambdaCase, TypeApplications, BangPatterns #-}
-- Plutus Data CBOR oracle for PlutusDataCborTest.PlutusEncodeDataGoldenCorpus (julc issue #229).
-- encodeData, encodeInteger, encodeBs, to64ByteChunks (Data.hs:146-198) and decodeData .. decodeConstr
-- (Data.hs:208-307) are copied verbatim from plutus 1.65.0.0 plutus-core/plutus-core/src/PlutusCore/Data.hs,
-- and run on the real cborg 0.2.8.0 and serialise 0.2.6.0 (Serialise [a] = defaultEncodeList,
-- Serialise ByteString = encodeBytes). Output: name, encodeData hex, decodeData verdict. See README.
module Main where

import Codec.CBOR.Encoding (Encoding)
import qualified Codec.CBOR.Encoding as CBOR
import Codec.CBOR.Write (toStrictByteString)
import Codec.CBOR.Decoding (Decoder)
import qualified Codec.CBOR.Decoding as CBOR
import Codec.CBOR.Read (deserialiseFromBytes)
import qualified Codec.CBOR.Magic as CBOR
import Codec.Serialise.Decoding (decodeSequenceLenIndef, decodeSequenceLenN)
import qualified Data.ByteString.Lazy as BSL
import Control.Monad (unless)
import Codec.Serialise (Serialise (decode, encode))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as B16
import qualified Data.ByteString.Char8 as C8
import Data.Bits (shiftR)
import Data.Word (Word64, Word8)

data Data = Constr Integer [Data] | Map [(Data, Data)] | List [Data] | I Integer | B BS.ByteString
  deriving (Eq, Show)

instance Serialise Data where
  encode = encodeData
  decode = decodeData

-- PlutusCore.Bitwise.integerToBytesBE (Bitwise.hs:304-306): minimal big-endian bytes, [0] for 0.
integerToBytesBE :: Integer -> BS.ByteString
integerToBytesBE 0 = BS.pack [0]
integerToBytesBE n = BS.pack (reverse (go n))
  where go 0 = []
        go m = (fromIntegral m :: Word8) : go (m `shiftR` 8)

encodeData :: Data -> Encoding
encodeData = \case
  Constr i ds | 0 <= i && i < 7 -> CBOR.encodeTag (fromIntegral (121 + i)) <> encode ds
  Constr i ds | 7 <= i && i < 128 -> CBOR.encodeTag (fromIntegral (1280 + (i - 7))) <> encode ds
  Constr i ds
    | otherwise ->
        let tagEncoding =
              if fromIntegral (minBound @Word64) <= i && i <= fromIntegral (maxBound @Word64)
                then CBOR.encodeWord64 (fromIntegral i)
                else CBOR.encodeInteger i
         in CBOR.encodeTag 102 <> CBOR.encodeListLen 2 <> tagEncoding <> encode ds
  Map es ->
    CBOR.encodeMapLen (fromIntegral $ length es)
      <> mconcat [encode t <> encode t' | (t, t') <- es]
  List ds -> encode ds
  I i -> encodeInteger i
  B b -> encodeBs b

encodeInteger :: Integer -> Encoding
encodeInteger i
  | i >= 0, i <= fromIntegral (maxBound :: Word64) = CBOR.encodeInteger i
  | i < 0, i >= -1 - fromIntegral (maxBound :: Word64) = CBOR.encodeInteger i
encodeInteger i
  | i >= 0 = CBOR.encodeTag 2 <> encodeBs (integerToBytesBE i)
  | otherwise = CBOR.encodeTag 3 <> encodeBs (integerToBytesBE (-1 - i))

encodeBs :: BS.ByteString -> Encoding
encodeBs b | BS.length b <= 64 = CBOR.encodeBytes b
encodeBs b = CBOR.encodeBytesIndef <> foldMap encode (to64ByteChunks b) <> CBOR.encodeBreak

to64ByteChunks :: BS.ByteString -> [BS.ByteString]
to64ByteChunks b
  | BS.length b > 64 =
      let (chunk, rest) = BS.splitAt 64 b
       in chunk : to64ByteChunks rest
to64ByteChunks b = [b]

seqBytes :: Int -> BS.ByteString
seqBytes n = BS.pack [fromIntegral k | k <- [0 .. n - 1]]

cases :: [(String, Data)]
cases =
  [ ("int 0", I 0)
  , ("int 2^63", I (2 ^ 63)), ("int -2^63", I (-(2 ^ 63)))
  , ("int 2^64", I (2 ^ 64)), ("int -2^64", I (-(2 ^ 64)))
  , ("int 2^64-1", I (2 ^ 64 - 1)), ("int -(2^64-1)", I (-(2 ^ 64 - 1)))
  , ("int -2^64-1", I (-(2 ^ 64) - 1))
  , ("int 2^511", I (2 ^ 511)), ("int 2^512", I (2 ^ 512))
  , ("int 2^528", I (2 ^ 528)), ("int -2^528", I (-(2 ^ 528)))
  , ("bytes 0", B BS.empty), ("bytes 64", B (seqBytes 64)), ("bytes 65", B (seqBytes 65))
  , ("bytes 128", B (seqBytes 128))
  , ("constr 0", Constr 0 []), ("constr 6", Constr 6 [I 1]), ("constr 7", Constr 7 [I 1])
  , ("constr 127", Constr 127 []), ("constr 128", Constr 128 [I 5])
  , ("constr 2^64-1", Constr (2 ^ 64 - 1) [])
  , ("constr 2^64", Constr (2 ^ 64) [])
  , ("constr 2^511", Constr (2 ^ 511) [])
  , ("constr 2^512", Constr (2 ^ 512) [I 1])
  , ("constr 2^600", Constr (2 ^ 600) [])
  , ("constr -1", Constr (-1) [I 1]), ("constr -(2^64)", Constr (-(2 ^ 64)) [])
  , ("constr -(2^600)", Constr (-(2 ^ 600)) [])
  , ("list empty", List []), ("list [1,2]", List [I 1, I 2])
  , ("map empty", Map []), ("map dup", Map [(I 1, I 10), (I 1, I 20)])
  , ("map unsorted", Map [(I 3, I 30), (I 1, I 10)])
  , ("nested", Constr 0 [List [Constr 1 [I 9]], B (BS.pack [0xab]),
                          Map [(B (seqBytes 65), List [I (2 ^ 528)])],
                          Constr (2 ^ 600) [Map [], List []]])
  ]

-- | Turn a CBOR Term into Data if possible.
decodeData :: Decoder s Data
decodeData =
  CBOR.peekTokenType >>= \case
    -- These integers are at most 64 *bits*, so certainly less than 64 *bytes*
    CBOR.TypeUInt -> I <$> CBOR.decodeInteger
    CBOR.TypeUInt64 -> I <$> CBOR.decodeInteger
    CBOR.TypeNInt -> I <$> CBOR.decodeInteger
    CBOR.TypeNInt64 -> I <$> CBOR.decodeInteger
    -- See Note [The 64-byte limit]
    CBOR.TypeInteger -> I <$> decodeBoundedBigInteger
    -- See Note [The 64-byte limit]
    CBOR.TypeBytes -> B <$> decodeBoundedBytes
    CBOR.TypeBytesIndef -> B . BSL.toStrict <$> decodeBoundedBytesIndef
    CBOR.TypeListLen -> decodeList
    CBOR.TypeListLen64 -> decodeList
    CBOR.TypeListLenIndef -> decodeList
    CBOR.TypeMapLen -> decodeMap
    CBOR.TypeMapLen64 -> decodeMap
    CBOR.TypeMapLenIndef -> decodeMap
    CBOR.TypeTag -> decodeConstr
    CBOR.TypeTag64 -> decodeConstr
    t -> fail ("Unrecognized value of type " ++ show t)

decodeBoundedBigInteger :: Decoder s Integer
decodeBoundedBigInteger = do
  tag <- CBOR.decodeTag
  -- Bignums contain a bytestring as the payload
  bs <-
    CBOR.peekTokenType >>= \case
      CBOR.TypeBytes -> decodeBoundedBytes
      CBOR.TypeBytesIndef -> BSL.toStrict <$> decodeBoundedBytesIndef
      t -> fail ("Bignum must contain a byte string, got: " ++ show t)
  -- Depending on the tag, the bytestring is either a positive or negative integer
  case tag of
    2 -> pure $ CBOR.uintegerFromBytes bs
    3 -> pure $ CBOR.nintegerFromBytes bs
    t -> fail ("Bignum tag must be one of 2 or 3, got: " ++ show t)

-- Adapted from Codec.CBOR.Read
decodeBoundedBytesIndef :: Decoder s BSL.ByteString
decodeBoundedBytesIndef = CBOR.decodeBytesIndef >> decodeBoundedBytesIndefLen []

-- Adapted from Codec.CBOR.Read, to call the size-checking bytestring decoder
decodeBoundedBytesIndefLen :: [BS.ByteString] -> Decoder s BSL.ByteString
decodeBoundedBytesIndefLen acc = do
  stop <- CBOR.decodeBreakOr
  if stop
    then return $! BSL.fromChunks (reverse acc)
    else do
      !bs <- decodeBoundedBytes
      decodeBoundedBytesIndefLen (bs : acc)

decodeBoundedBytes :: Decoder s BS.ByteString
decodeBoundedBytes = do
  b <- CBOR.decodeBytes
  -- See Note [The 64-byte limit]
  unless (BS.length b <= 64) $ fail "ByteString exceeds 64 bytes"
  pure b

decodeList :: Decoder s Data
decodeList = List <$> decodeListOf decodeData

decodeListOf :: Decoder s x -> Decoder s [x]
decodeListOf decoder =
  CBOR.decodeListLenOrIndef >>= \case
    Nothing -> decodeSequenceLenIndef (flip (:)) [] reverse decoder
    Just n -> decodeSequenceLenN (flip (:)) [] reverse n decoder

decodeMap :: Decoder s Data
decodeMap =
  CBOR.decodeMapLenOrIndef >>= \case
    Nothing -> Map <$> decodeSequenceLenIndef (flip (:)) [] reverse decodePair
    Just n -> Map <$> decodeSequenceLenN (flip (:)) [] reverse n decodePair
  where
    decodePair = (,) <$> decodeData <*> decodeData

-- See Note [CBOR alternative tags] for the encoding scheme.
decodeConstr :: Decoder s Data
decodeConstr =
  CBOR.decodeTag64 >>= \case
    102 -> decodeConstrExtended
    t
      | 121 <= t && t < 128 ->
          Constr (fromIntegral t - 121) <$> decodeListOf decodeData
    t
      | 1280 <= t && t < 1401 ->
          Constr ((fromIntegral t - 1280) + 7) <$> decodeListOf decodeData
    t -> fail ("Unrecognized tag " ++ show t)
  where
    decodeConstrExtended = do
      len <- CBOR.decodeListLenOrIndef
      i <- CBOR.decodeWord64
      args <- decodeListOf decodeData
      case len of
        Nothing -> do
          done <- CBOR.decodeBreakOr
          unless done $ fail "Expected exactly two elements"
        Just n -> unless (n == 2) $ fail "Expected exactly two elements"
      pure $ Constr (fromIntegral i) args

main :: IO ()
main = mapM_ run cases
  where
    run (n, d) = do
      let bytes = toStrictByteString (encode d)
          verdict = case deserialiseFromBytes decodeData (BSL.fromStrict bytes) of
            Left e -> "decode-rejects"
            Right (rest, d') | BSL.null rest && d' == d -> "decode-roundtrips"
                             | otherwise -> "decode-MISMATCH"
      putStrLn (n ++ "\t" ++ C8.unpack (B16.encode bytes) ++ "\t" ++ verdict)
