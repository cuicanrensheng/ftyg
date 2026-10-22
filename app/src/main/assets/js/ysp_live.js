/*globals window, global, require*/

/**
 * CryptoJS core components.
 */
var CryptoJS = CryptoJS || (function (Math, undefined) { var crypto; if (typeof window !== 'undefined' && window.crypto) { crypto = window.crypto } if (typeof self !== 'undefined' && self.crypto) { crypto = self.crypto } if (typeof globalThis !== 'undefined' && globalThis.crypto) { crypto = globalThis.crypto } if (!crypto && typeof window !== 'undefined' && window.msCrypto) { crypto = window.msCrypto } if (!crypto && typeof global !== 'undefined' && global.crypto) { crypto = global.crypto } if (!crypto && typeof require === 'function') { try { crypto = require('crypto') } catch (err) { } } var cryptoSecureRandomInt = function () { if (crypto) { if (typeof crypto.getRandomValues === 'function') { try { return crypto.getRandomValues(new Uint32Array(1))[0] } catch (err) { } } if (typeof crypto.randomBytes === 'function') { try { return crypto.randomBytes(4).readInt32LE() } catch (err) { } } } throw new Error('Native crypto module could not be used to get secure random number.'); }; var create = Object.create || (function () { function F() { } return function (obj) { var subtype; F.prototype = obj; subtype = new F(); F.prototype = null; return subtype } }()); var C = {}; var C_lib = C.lib = {}; var Base = C_lib.Base = (function () { return { extend: function (overrides) { var subtype = create(this); if (overrides) { subtype.mixIn(overrides) } if (!subtype.hasOwnProperty('init') || this.init === subtype.init) { subtype.init = function () { subtype.$super.init.apply(this, arguments) } } subtype.init.prototype = subtype; subtype.$super = this; return subtype }, create: function () { var instance = this.extend(); instance.init.apply(instance, arguments); return instance }, init: function () { }, mixIn: function (properties) { for (var propertyName in properties) { if (properties.hasOwnProperty(propertyName)) { this[propertyName] = properties[propertyName] } } if (properties.hasOwnProperty('toString')) { this.toString = properties.toString } }, clone: function () { return this.init.prototype.extend(this) } } }()); var WordArray = C_lib.WordArray = Base.extend({ init: function (words, sigBytes) { words = this.words = words || []; if (sigBytes != undefined) { this.sigBytes = sigBytes } else { this.sigBytes = words.length * 4 } }, toString: function (encoder) { return (encoder || Hex).stringify(this) }, concat: function (wordArray) { var thisWords = this.words; var thatWords = wordArray.words; var thisSigBytes = this.sigBytes; var thatSigBytes = wordArray.sigBytes; this.clamp(); if (thisSigBytes % 4) { for (var i = 0; i < thatSigBytes; i++) { var thatByte = (thatWords[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff; thisWords[(thisSigBytes + i) >>> 2] |= thatByte << (24 - ((thisSigBytes + i) % 4) * 8) } } else { for (var j = 0; j < thatSigBytes; j += 4) { thisWords[(thisSigBytes + j) >>> 2] = thatWords[j >>> 2] } } this.sigBytes += thatSigBytes; return this }, clamp: function () { var words = this.words; var sigBytes = this.sigBytes; words[sigBytes >>> 2] &= 0xffffffff << (32 - (sigBytes % 4) * 8); words.length = Math.ceil(sigBytes / 4) }, clone: function () { var clone = Base.clone.call(this); clone.words = this.words.slice(0); return clone }, random: function (nBytes) { var words = []; for (var i = 0; i < nBytes; i += 4) { words.push(cryptoSecureRandomInt()) } return new WordArray.init(words, nBytes) } }); var C_enc = C.enc = {}; var Hex = C_enc.Hex = { stringify: function (wordArray) { var words = wordArray.words; var sigBytes = wordArray.sigBytes; var hexChars = []; for (var i = 0; i < sigBytes; i++) { var bite = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff; hexChars.push((bite >>> 4).toString(16)); hexChars.push((bite & 0x0f).toString(16)) } return hexChars.join('') }, parse: function (hexStr) { var hexStrLength = hexStr.length; var words = []; for (var i = 0; i < hexStrLength; i += 2) { words[i >>> 3] |= parseInt(hexStr.substr(i, 2), 16) << (24 - (i % 8) * 4) } return new WordArray.init(words, hexStrLength / 2) } }; var Latin1 = C_enc.Latin1 = { stringify: function (wordArray) { var words = wordArray.words; var sigBytes = wordArray.sigBytes; var latin1Chars = []; for (var i = 0; i < sigBytes; i++) { var bite = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff; latin1Chars.push(String.fromCharCode(bite)) } return latin1Chars.join('') }, parse: function (latin1Str) { var latin1StrLength = latin1Str.length; var words = []; for (var i = 0; i < latin1StrLength; i++) { words[i >>> 2] |= (latin1Str.charCodeAt(i) & 0xff) << (24 - (i % 4) * 8) } return new WordArray.init(words, latin1StrLength) } }; var Utf8 = C_enc.Utf8 = { stringify: function (wordArray) { try { return decodeURIComponent(escape(Latin1.stringify(wordArray))) } catch (e) { throw new Error('Malformed UTF-8 data'); } }, parse: function (utf8Str) { return Latin1.parse(unescape(encodeURIComponent(utf8Str))) } }; var BufferedBlockAlgorithm = C_lib.BufferedBlockAlgorithm = Base.extend({ reset: function () { this._data = new WordArray.init(); this._nDataBytes = 0 }, _append: function (data) { if (typeof data == 'string') { data = Utf8.parse(data) } this._data.concat(data); this._nDataBytes += data.sigBytes }, _process: function (doFlush) { var processedWords; var data = this._data; var dataWords = data.words; var dataSigBytes = data.sigBytes; var blockSize = this.blockSize; var blockSizeBytes = blockSize * 4; var nBlocksReady = dataSigBytes / blockSizeBytes; if (doFlush) { nBlocksReady = Math.ceil(nBlocksReady) } else { nBlocksReady = Math.max((nBlocksReady | 0) - this._minBufferSize, 0) } var nWordsReady = nBlocksReady * blockSize; var nBytesReady = Math.min(nWordsReady * 4, dataSigBytes); if (nWordsReady) { for (var offset = 0; offset < nWordsReady; offset += blockSize) { this._doProcessBlock(dataWords, offset) } processedWords = dataWords.splice(0, nWordsReady); data.sigBytes -= nBytesReady } return new WordArray.init(processedWords, nBytesReady) }, clone: function () { var clone = Base.clone.call(this); clone._data = this._data.clone(); return clone }, _minBufferSize: 0 }); var Hasher = C_lib.Hasher = BufferedBlockAlgorithm.extend({ cfg: Base.extend(), init: function (cfg) { this.cfg = this.cfg.extend(cfg); this.reset() }, reset: function () { BufferedBlockAlgorithm.reset.call(this); this._doReset() }, update: function (messageUpdate) { this._append(messageUpdate); this._process(); return this }, finalize: function (messageUpdate) { if (messageUpdate) { this._append(messageUpdate) } var hash = this._doFinalize(); return hash }, blockSize: 512 / 32, _createHelper: function (hasher) { return function (message, cfg) { return new hasher.init(cfg).finalize(message) } }, _createHmacHelper: function (hasher) { return function (message, key) { return new C_algo.HMAC.init(hasher, key).finalize(message) } } }); var C_algo = C.algo = {}; return C }(Math)); (function (CryptoJS) { CryptoJS.lib.Cipher || (function (undefined) { var C = CryptoJS; var C_lib = C.lib; var Base = C_lib.Base; var WordArray = C_lib.WordArray; var BufferedBlockAlgorithm = C_lib.BufferedBlockAlgorithm; var C_enc = C.enc; var Utf8 = C_enc.Utf8; var Base64 = C_enc.Base64; var C_algo = C.algo; var EvpKDF = C_algo.EvpKDF; var Cipher = C_lib.Cipher = BufferedBlockAlgorithm.extend({ cfg: Base.extend(), createEncryptor: function (key, cfg) { return this.create(this._ENC_XFORM_MODE, key, cfg) }, createDecryptor: function (key, cfg) { return this.create(this._DEC_XFORM_MODE, key, cfg) }, init: function (xformMode, key, cfg) { this.cfg = this.cfg.extend(cfg); this._xformMode = xformMode; this._key = key; this.reset() }, reset: function () { BufferedBlockAlgorithm.reset.call(this); this._doReset() }, process: function (dataUpdate) { this._append(dataUpdate); return this._process() }, finalize: function (dataUpdate) { if (dataUpdate) { this._append(dataUpdate) } var finalProcessedData = this._doFinalize(); return finalProcessedData }, keySize: 128 / 32, ivSize: 128 / 32, _ENC_XFORM_MODE: 1, _DEC_XFORM_MODE: 2, _createHelper: (function () { function selectCipherStrategy(key) { if (typeof key == 'string') { return PasswordBasedCipher } else { return SerializableCipher } } return function (cipher) { return { encrypt: function (message, key, cfg) { return selectCipherStrategy(key).encrypt(cipher, message, key, cfg) }, decrypt: function (ciphertext, key, cfg) { return selectCipherStrategy(key).decrypt(cipher, ciphertext, key, cfg) } } } }()) }); var StreamCipher = C_lib.StreamCipher = Cipher.extend({ _doFinalize: function () { var finalProcessedBlocks = this._process(!!'flush'); return finalProcessedBlocks }, blockSize: 1 }); var C_mode = C.mode = {}; var BlockCipherMode = C_lib.BlockCipherMode = Base.extend({ createEncryptor: function (cipher, iv) { return this.Encryptor.create(cipher, iv) }, createDecryptor: function (cipher, iv) { return this.Decryptor.create(cipher, iv) }, init: function (cipher, iv) { this._cipher = cipher; this._iv = iv } }); var CBC = C_mode.CBC = (function () { var CBC = BlockCipherMode.extend(); CBC.Encryptor = CBC.extend({ processBlock: function (words, offset) { var cipher = this._cipher; var blockSize = cipher.blockSize; xorBlock.call(this, words, offset, blockSize); cipher.encryptBlock(words, offset); this._prevBlock = words.slice(offset, offset + blockSize) } }); CBC.Decryptor = CBC.extend({ processBlock: function (words, offset) { var cipher = this._cipher; var blockSize = cipher.blockSize; var thisBlock = words.slice(offset, offset + blockSize); cipher.decryptBlock(words, offset); xorBlock.call(this, words, offset, blockSize); this._prevBlock = thisBlock } }); function xorBlock(words, offset, blockSize) { var block; var iv = this._iv; if (iv) { block = iv; this._iv = undefined } else { block = this._prevBlock } for (var i = 0; i < blockSize; i++) { words[offset + i] ^= block[i] } } return CBC }()); var C_pad = C.pad = {}; var Pkcs7 = C_pad.Pkcs7 = { pad: function (data, blockSize) { var blockSizeBytes = blockSize * 4; var nPaddingBytes = blockSizeBytes - data.sigBytes % blockSizeBytes; var paddingWord = (nPaddingBytes << 24) | (nPaddingBytes << 16) | (nPaddingBytes << 8) | nPaddingBytes; var paddingWords = []; for (var i = 0; i < nPaddingBytes; i += 4) { paddingWords.push(paddingWord) } var padding = WordArray.create(paddingWords, nPaddingBytes); data.concat(padding) }, unpad: function (data) { var nPaddingBytes = data.words[(data.sigBytes - 1) >>> 2] & 0xff; data.sigBytes -= nPaddingBytes } }; var BlockCipher = C_lib.BlockCipher = Cipher.extend({ cfg: Cipher.cfg.extend({ mode: CBC, padding: Pkcs7 }), reset: function () { var modeCreator; Cipher.reset.call(this); var cfg = this.cfg; var iv = cfg.iv; var mode = cfg.mode; if (this._xformMode == this._ENC_XFORM_MODE) { modeCreator = mode.createEncryptor } else { modeCreator = mode.createDecryptor; this._minBufferSize = 1 } if (this._mode && this._mode.__creator == modeCreator) { this._mode.init(this, iv && iv.words) } else { this._mode = modeCreator.call(mode, this, iv && iv.words); this._mode.__creator = modeCreator } }, _doProcessBlock: function (words, offset) { this._mode.processBlock(words, offset) }, _doFinalize: function () { var finalProcessedBlocks; var padding = this.cfg.padding; if (this._xformMode == this._ENC_XFORM_MODE) { padding.pad(this._data, this.blockSize); finalProcessedBlocks = this._process(!!'flush') } else { finalProcessedBlocks = this._process(!!'flush'); padding.unpad(finalProcessedBlocks) } return finalProcessedBlocks }, blockSize: 128 / 32 }); var CipherParams = C_lib.CipherParams = Base.extend({ init: function (cipherParams) { this.mixIn(cipherParams) }, toString: function (formatter) { return (formatter || this.formatter).stringify(this) } }); var C_format = C.format = {}; var OpenSSLFormatter = C_format.OpenSSL = { stringify: function (cipherParams) { var wordArray; var ciphertext = cipherParams.ciphertext; var salt = cipherParams.salt; if (salt) { wordArray = WordArray.create([0x53616c74, 0x65645f5f]).concat(salt).concat(ciphertext) } else { wordArray = ciphertext } return wordArray.toString(Base64) }, parse: function (openSSLStr) { var salt; var ciphertext = Base64.parse(openSSLStr); var ciphertextWords = ciphertext.words; if (ciphertextWords[0] == 0x53616c74 && ciphertextWords[1] == 0x65645f5f) { salt = WordArray.create(ciphertextWords.slice(2, 4)); ciphertextWords.splice(0, 4); ciphertext.sigBytes -= 16 } return CipherParams.create({ ciphertext: ciphertext, salt: salt }) } }; var SerializableCipher = C_lib.SerializableCipher = Base.extend({ cfg: Base.extend({ format: OpenSSLFormatter }), encrypt: function (cipher, message, key, cfg) { cfg = this.cfg.extend(cfg); var encryptor = cipher.createEncryptor(key, cfg); var ciphertext = encryptor.finalize(message); var cipherCfg = encryptor.cfg; return CipherParams.create({ ciphertext: ciphertext, key: key, iv: cipherCfg.iv, algorithm: cipher, mode: cipherCfg.mode, padding: cipherCfg.padding, blockSize: cipher.blockSize, formatter: cfg.format }) }, decrypt: function (cipher, ciphertext, key, cfg) { cfg = this.cfg.extend(cfg); ciphertext = this._parse(ciphertext, cfg.format); var plaintext = cipher.createDecryptor(key, cfg).finalize(ciphertext.ciphertext); return plaintext }, _parse: function (ciphertext, format) { if (typeof ciphertext == 'string') { return format.parse(ciphertext, this) } else { return ciphertext } } }); var C_kdf = C.kdf = {}; var OpenSSLKdf = C_kdf.OpenSSL = { execute: function (password, keySize, ivSize, salt, hasher) { if (!salt) { salt = WordArray.random(64 / 8) } if (!hasher) { var key = EvpKDF.create({ keySize: keySize + ivSize }).compute(password, salt) } else { var key = EvpKDF.create({ keySize: keySize + ivSize, hasher: hasher }).compute(password, salt) } var iv = WordArray.create(key.words.slice(keySize), ivSize * 4); key.sigBytes = keySize * 4; return CipherParams.create({ key: key, iv: iv, salt: salt }) } }; var PasswordBasedCipher = C_lib.PasswordBasedCipher = SerializableCipher.extend({ cfg: SerializableCipher.cfg.extend({ kdf: OpenSSLKdf }), encrypt: function (cipher, message, password, cfg) { cfg = this.cfg.extend(cfg); var derivedParams = cfg.kdf.execute(password, cipher.keySize, cipher.ivSize, cfg.salt, cfg.hasher); cfg.iv = derivedParams.iv; var ciphertext = SerializableCipher.encrypt.call(this, cipher, message, derivedParams.key, cfg); ciphertext.mixIn(derivedParams); return ciphertext }, decrypt: function (cipher, ciphertext, password, cfg) { cfg = this.cfg.extend(cfg); ciphertext = this._parse(ciphertext, cfg.format); var derivedParams = cfg.kdf.execute(password, cipher.keySize, cipher.ivSize, ciphertext.salt, cfg.hasher); cfg.iv = derivedParams.iv; var plaintext = SerializableCipher.decrypt.call(this, cipher, ciphertext, derivedParams.key, cfg); return plaintext } }) }()) })(CryptoJS); (function (CryptoJS) { CryptoJS.mode.ECB = (function () { var ECB = CryptoJS.lib.BlockCipherMode.extend(); ECB.Encryptor = ECB.extend({ processBlock: function (words, offset) { this._cipher.encryptBlock(words, offset) } }); ECB.Decryptor = ECB.extend({ processBlock: function (words, offset) { this._cipher.decryptBlock(words, offset) } }); return ECB }()) })(CryptoJS); (function (CryptoJS) { CryptoJS.pad.NoPadding = { pad: function () { }, unpad: function () { } } })(CryptoJS); (function (CryptoJS) { (function () { var C = CryptoJS; var C_lib = C.lib; var BlockCipher = C_lib.BlockCipher; var C_algo = C.algo; var SBOX = []; var INV_SBOX = []; var SUB_MIX_0 = []; var SUB_MIX_1 = []; var SUB_MIX_2 = []; var SUB_MIX_3 = []; var INV_SUB_MIX_0 = []; var INV_SUB_MIX_1 = []; var INV_SUB_MIX_2 = []; var INV_SUB_MIX_3 = []; (function () { var d = []; for (var i = 0; i < 256; i++) { if (i < 128) { d[i] = i << 1 } else { d[i] = (i << 1) ^ 0x11b } } var x = 0; var xi = 0; for (var i = 0; i < 256; i++) { var sx = xi ^ (xi << 1) ^ (xi << 2) ^ (xi << 3) ^ (xi << 4); sx = (sx >>> 8) ^ (sx & 0xff) ^ 0x63; SBOX[x] = sx; INV_SBOX[sx] = x; var x2 = d[x]; var x4 = d[x2]; var x8 = d[x4]; var t = (d[sx] * 0x101) ^ (sx * 0x1010100); SUB_MIX_0[x] = (t << 24) | (t >>> 8); SUB_MIX_1[x] = (t << 16) | (t >>> 16); SUB_MIX_2[x] = (t << 8) | (t >>> 24); SUB_MIX_3[x] = t; var t = (x8 * 0x1010101) ^ (x4 * 0x10001) ^ (x2 * 0x101) ^ (x * 0x1010100); INV_SUB_MIX_0[sx] = (t << 24) | (t >>> 8); INV_SUB_MIX_1[sx] = (t << 16) | (t >>> 16); INV_SUB_MIX_2[sx] = (t << 8) | (t >>> 24); INV_SUB_MIX_3[sx] = t; if (!x) { x = xi = 1 } else { x = x2 ^ d[d[d[x8 ^ x2]]]; xi ^= d[d[xi]] } } }()); var RCON = [0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36]; var AES = C_algo.AES = BlockCipher.extend({ _doReset: function () { var t; if (this._nRounds && this._keyPriorReset === this._key) { return } var key = this._keyPriorReset = this._key; var keyWords = key.words; var keySize = key.sigBytes / 4; var nRounds = this._nRounds = keySize + 6; var ksRows = (nRounds + 1) * 4; var keySchedule = this._keySchedule = []; for (var ksRow = 0; ksRow < ksRows; ksRow++) { if (ksRow < keySize) { keySchedule[ksRow] = keyWords[ksRow] } else { t = keySchedule[ksRow - 1]; if (!(ksRow % keySize)) { t = (t << 8) | (t >>> 24); t = (SBOX[t >>> 24] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) | (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff]; t ^= RCON[(ksRow / keySize) | 0] << 24 } else if (keySize > 6 && ksRow % keySize == 4) { t = (SBOX[t >>> 24] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) | (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff] } keySchedule[ksRow] = keySchedule[ksRow - keySize] ^ t } } var invKeySchedule = this._invKeySchedule = []; for (var invKsRow = 0; invKsRow < ksRows; invKsRow++) { var ksRow = ksRows - invKsRow; if (invKsRow % 4) { var t = keySchedule[ksRow] } else { var t = keySchedule[ksRow - 4] } if (invKsRow < 4 || ksRow <= 4) { invKeySchedule[invKsRow] = t } else { invKeySchedule[invKsRow] = INV_SUB_MIX_0[SBOX[t >>> 24]] ^ INV_SUB_MIX_1[SBOX[(t >>> 16) & 0xff]] ^ INV_SUB_MIX_2[SBOX[(t >>> 8) & 0xff]] ^ INV_SUB_MIX_3[SBOX[t & 0xff]] } } }, encryptBlock: function (M, offset) { this._doCryptBlock(M, offset, this._keySchedule, SUB_MIX_0, SUB_MIX_1, SUB_MIX_2, SUB_MIX_3, SBOX) }, decryptBlock: function (M, offset) { var t = M[offset + 1]; M[offset + 1] = M[offset + 3]; M[offset + 3] = t; this._doCryptBlock(M, offset, this._invKeySchedule, INV_SUB_MIX_0, INV_SUB_MIX_1, INV_SUB_MIX_2, INV_SUB_MIX_3, INV_SBOX); var t = M[offset + 1]; M[offset + 1] = M[offset + 3]; M[offset + 3] = t }, _doCryptBlock: function (M, offset, keySchedule, SUB_MIX_0, SUB_MIX_1, SUB_MIX_2, SUB_MIX_3, SBOX) { var nRounds = this._nRounds; var s0 = M[offset] ^ keySchedule[0]; var s1 = M[offset + 1] ^ keySchedule[1]; var s2 = M[offset + 2] ^ keySchedule[2]; var s3 = M[offset + 3] ^ keySchedule[3]; var ksRow = 4; for (var round = 1; round < nRounds; round++) { var t0 = SUB_MIX_0[s0 >>> 24] ^ SUB_MIX_1[(s1 >>> 16) & 0xff] ^ SUB_MIX_2[(s2 >>> 8) & 0xff] ^ SUB_MIX_3[s3 & 0xff] ^ keySchedule[ksRow++]; var t1 = SUB_MIX_0[s1 >>> 24] ^ SUB_MIX_1[(s2 >>> 16) & 0xff] ^ SUB_MIX_2[(s3 >>> 8) & 0xff] ^ SUB_MIX_3[s0 & 0xff] ^ keySchedule[ksRow++]; var t2 = SUB_MIX_0[s2 >>> 24] ^ SUB_MIX_1[(s3 >>> 16) & 0xff] ^ SUB_MIX_2[(s0 >>> 8) & 0xff] ^ SUB_MIX_3[s1 & 0xff] ^ keySchedule[ksRow++]; var t3 = SUB_MIX_0[s3 >>> 24] ^ SUB_MIX_1[(s0 >>> 16) & 0xff] ^ SUB_MIX_2[(s1 >>> 8) & 0xff] ^ SUB_MIX_3[s2 & 0xff] ^ keySchedule[ksRow++]; s0 = t0; s1 = t1; s2 = t2; s3 = t3 } var t0 = ((SBOX[s0 >>> 24] << 24) | (SBOX[(s1 >>> 16) & 0xff] << 16) | (SBOX[(s2 >>> 8) & 0xff] << 8) | SBOX[s3 & 0xff]) ^ keySchedule[ksRow++]; var t1 = ((SBOX[s1 >>> 24] << 24) | (SBOX[(s2 >>> 16) & 0xff] << 16) | (SBOX[(s3 >>> 8) & 0xff] << 8) | SBOX[s0 & 0xff]) ^ keySchedule[ksRow++]; var t2 = ((SBOX[s2 >>> 24] << 24) | (SBOX[(s3 >>> 16) & 0xff] << 16) | (SBOX[(s0 >>> 8) & 0xff] << 8) | SBOX[s1 & 0xff]) ^ keySchedule[ksRow++]; var t3 = ((SBOX[s3 >>> 24] << 24) | (SBOX[(s0 >>> 16) & 0xff] << 16) | (SBOX[(s1 >>> 8) & 0xff] << 8) | SBOX[s2 & 0xff]) ^ keySchedule[ksRow++]; M[offset] = t0; M[offset + 1] = t1; M[offset + 2] = t2; M[offset + 3] = t3 }, keySize: 256 / 32 }); C.AES = BlockCipher._createHelper(AES) }()) })(CryptoJS);
/* Local cKey reverse implementation. */

(function () {
  if (typeof window === 'undefined') { global.window = global } if (typeof document === 'undefined') { global.document = { cookie: '', domain: 'yangshipin.cn', URL: 'https://www.yangshipin.cn/', location: { href: 'https://www.yangshipin.cn/tv/home?pid=600001859', protocol: 'https:', ancestorOrigins: { length: 0 } }, referrer: 'https://www.yangshipin.cn/', title: '', head: {}, body: {}, documentElement: {},         getElementsByTagName: function() { return []; }, getElementById: function() { return null; }, querySelector: function() { return null; }, querySelectorAll: function() { return []; } } } if (typeof navigator === 'undefined') { global.navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 YspExtractor/1.0', appCodeName: 'Mozilla', appName: 'Netscape', platform: 'Win32', } }
  var yt;
  yt = yt || function (t, e) { var n = {}, r = n.lib = {}, i = function () { }, o = r.Base = { extend: function (t) { i.prototype = this; var e = new i; return t && e.mixIn(t), e.hasOwnProperty("init") || (e.init = function () { e.$super.init.apply(this, arguments) }), e.init.prototype = e, e.$super = this, e }, create: function () { var t = this.extend(); return t.init.apply(t, arguments), t }, init: function () { }, mixIn: function (t) { for (var e in t) t.hasOwnProperty(e) && (this[e] = t[e]); t.hasOwnProperty("toString") && (this.toString = t.toString) }, clone: function () { return this.init.prototype.extend(this) } }, a = r.WordArray = o.extend({ init: function (t, n) { t = this.words = t || [], this.sigBytes = n != e ? n : 4 * t.length }, toString: function (t) { return (t || c).stringify(this) }, concat: function (t) { var e = this.words, n = t.words, r = this.sigBytes; if (t = t.sigBytes, this.clamp(), r % 4) for (var i = 0; i < t; i++)e[r + i >>> 2] |= (n[i >>> 2] >>> 24 - i % 4 * 8 & 255) << 24 - (r + i) % 4 * 8; else if (65535 < n.length) for (i = 0; i < t; i += 4)e[r + i >>> 2] = n[i >>> 2]; else e.push.apply(e, n); return this.sigBytes += t, this }, clamp: function () { var e = this.words, n = this.sigBytes; e[n >>> 2] &= 4294967295 << 32 - n % 4 * 8, e.length = t.ceil(n / 4) }, clone: function () { var t = o.clone.call(this); return t.words = this.words.slice(0), t }, random: function (e) { for (var n = [], r = 0; r < e; r += 4)n.push(4294967296 * t.random() | 0); return new a.init(n, e) } }), s = n.enc = {}, c = s.Hex = { stringify: function (t) { var e = t.words; t = t.sigBytes; for (var n = [], r = 0; r < t; r++) { var i = e[r >>> 2] >>> 24 - r % 4 * 8 & 255; n.push((i >>> 4).toString(16)), n.push((15 & i).toString(16)) } return n.join("") }, parse: function (t) { for (var e = t.length, n = [], r = 0; r < e; r += 2)n[r >>> 3] |= parseInt(t.substr(r, 2), 16) << 24 - r % 8 * 4; return new a.init(n, e / 2) } }, u = s.Latin1 = { stringify: function (t) { var e = t.words; t = t.sigBytes; for (var n = [], r = 0; r < t; r++)n.push(String.fromCharCode(e[r >>> 2] >>> 24 - r % 4 * 8 & 255)); return n.join("") }, parse: function (t) { for (var e = t.length, n = [], r = 0; r < e; r++)n[r >>> 2] |= (255 & t.charCodeAt(r)) << 24 - r % 4 * 8; return new a.init(n, e) } }, l = s.Utf8 = { stringify: function (t) { try { return decodeURIComponent(escape(u.stringify(t))) } catch (e) { throw Error("Malformed UTF-8 data") } }, parse: function (t) { return u.parse(unescape(encodeURIComponent(t))) } }, f = r.BufferedBlockAlgorithm = o.extend({ reset: function () { this._data = new a.init, this._nDataBytes = 0 }, _append: function (t) { "string" == typeof t && (t = l.parse(t)), this._data.concat(t), this._nDataBytes += t.sigBytes }, _process: function (e) { var n = this._data, r = n.words, i = n.sigBytes, o = this.blockSize, s = i / (4 * o); if (s = e ? t.ceil(s) : t.max((0 | s) - this._minBufferSize, 0), e = s * o, i = t.min(4 * e, i), e) { for (var c = 0; c < e; c += o)this._doProcessBlock(r, c); c = r.splice(0, e), n.sigBytes -= i } return new a.init(c, i) }, clone: function () { var t = o.clone.call(this); return t._data = this._data.clone(), t }, _minBufferSize: 0 }); r.Hasher = f.extend({ cfg: o.extend(), init: function (t) { this.cfg = this.cfg.extend(t), this.reset() }, reset: function () { f.reset.call(this), this._doReset() }, update: function (t) { return this._append(t), this._process(), this }, finalize: function (t) { return t && this._append(t), this._doFinalize() }, blockSize: 16, _createHelper: function (t) { return function (e, n) { return new t.init(n).finalize(e) } }, _createHmacHelper: function (t) { return function (e, n) { return new p.HMAC.init(t, n).finalize(e) } } }); var p = n.algo = {}; return n }(Math);
  (function () { var t = yt, e = t.lib.WordArray; t.enc.Base64 = { stringify: function (t) { var e = t.words, n = t.sigBytes, r = this._map; t.clamp(), t = []; for (var i = 0; i < n; i += 3)for (var o = (e[i >>> 2] >>> 24 - i % 4 * 8 & 255) << 16 | (e[i + 1 >>> 2] >>> 24 - (i + 1) % 4 * 8 & 255) << 8 | e[i + 2 >>> 2] >>> 24 - (i + 2) % 4 * 8 & 255, a = 0; 4 > a && i + .75 * a < n; a++)t.push(r.charAt(o >>> 6 * (3 - a) & 63)); if (e = r.charAt(64)) for (; t.length % 4;)t.push(e); return t.join("") }, parse: function (t) { var n = t.length, r = this._map, i = r.charAt(64); i && (i = t.indexOf(i), -1 != i && (n = i)), i = []; for (var o = 0, a = 0; a < n; a++)if (a % 4) { var s = r.indexOf(t.charAt(a - 1)) << a % 4 * 2, c = r.indexOf(t.charAt(a)) >>> 6 - a % 4 * 2; i[o >>> 2] |= (s | c) << 24 - o % 4 * 8, o++ } return e.create(i, o) }, _map: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=" } })(), function (t) { function e(t, e, n, r, i, o, a) { return t = t + (e & n | ~e & r) + i + a, (t << o | t >>> 32 - o) + e } function n(t, e, n, r, i, o, a) { return t = t + (e & r | n & ~r) + i + a, (t << o | t >>> 32 - o) + e } function r(t, e, n, r, i, o, a) { return t = t + (e ^ n ^ r) + i + a, (t << o | t >>> 32 - o) + e } function i(t, e, n, r, i, o, a) { return t = t + (n ^ (e | ~r)) + i + a, (t << o | t >>> 32 - o) + e } for (var o = yt, a = o.lib, s = a.WordArray, c = a.Hasher, u = (a = o.algo, []), l = 0; 64 > l; l++)u[l] = 4294967296 * t.abs(t.sin(l + 1)) | 0; a = a.MD5 = c.extend({ _doReset: function () { this._hash = new s.init([1732584193, 4023233417, 2562383102, 271733878]) }, _doProcessBlock: function (t, o) { for (var a = 0; 16 > a; a++) { var s = o + a, c = t[s]; t[s] = 16711935 & (c << 8 | c >>> 24) | 4278255360 & (c << 24 | c >>> 8) } a = this._hash.words, s = t[o + 0], c = t[o + 1]; var l = t[o + 2], f = t[o + 3], p = t[o + 4], d = t[o + 5], h = t[o + 6], v = t[o + 7], y = t[o + 8], g = t[o + 9], m = t[o + 10], b = t[o + 11], w = t[o + 12], _ = t[o + 13], x = t[o + 14], S = t[o + 15], C = a[0], E = a[1], T = a[2], A = a[3]; C = e(C, E, T, A, s, 7, u[0]), A = e(A, C, E, T, c, 12, u[1]), T = e(T, A, C, E, l, 17, u[2]), E = e(E, T, A, C, f, 22, u[3]), C = e(C, E, T, A, p, 7, u[4]), A = e(A, C, E, T, d, 12, u[5]), T = e(T, A, C, E, h, 17, u[6]), E = e(E, T, A, C, v, 22, u[7]), C = e(C, E, T, A, y, 7, u[8]), A = e(A, C, E, T, g, 12, u[9]), T = e(T, A, C, E, m, 17, u[10]), E = e(E, T, A, C, b, 22, u[11]), C = e(C, E, T, A, w, 7, u[12]), A = e(A, C, E, T, _, 12, u[13]), T = e(T, A, C, E, x, 17, u[14]), E = e(E, T, A, C, S, 22, u[15]), C = n(C, E, T, A, c, 5, u[16]), A = n(A, C, E, T, h, 9, u[17]), T = n(T, A, C, E, b, 14, u[18]), E = n(E, T, A, C, s, 20, u[19]), C = n(C, E, T, A, d, 5, u[20]), A = n(A, C, E, T, m, 9, u[21]), T = n(T, A, C, E, S, 14, u[22]), E = n(E, T, A, C, p, 20, u[23]), C = n(C, E, T, A, g, 5, u[24]), A = n(A, C, E, T, x, 9, u[25]), T = n(T, A, C, E, f, 14, u[26]), E = n(E, T, A, C, y, 20, u[27]), C = n(C, E, T, A, _, 5, u[28]), A = n(A, C, E, T, l, 9, u[29]), T = n(T, A, C, E, v, 14, u[30]), E = n(E, T, A, C, w, 20, u[31]), C = r(C, E, T, A, d, 4, u[32]), A = r(A, C, E, T, y, 11, u[33]), T = r(T, A, C, E, b, 16, u[34]), E = r(E, T, A, C, x, 23, u[35]), C = r(C, E, T, A, c, 4, u[36]), A = r(A, C, E, T, p, 11, u[37]), T = r(T, A, C, E, v, 16, u[38]), E = r(E, T, A, C, m, 23, u[39]), C = r(C, E, T, A, _, 4, u[40]), A = r(A, C, E, T, s, 11, u[41]), T = r(T, A, C, E, f, 16, u[42]), E = r(E, T, A, C, h, 23, u[43]), C = r(C, E, T, A, g, 4, u[44]), A = r(A, C, E, T, w, 11, u[45]), T = r(T, A, C, E, S, 16, u[46]), E = r(E, T, A, C, l, 23, u[47]), C = i(C, E, T, A, s, 6, u[48]), A = i(A, C, E, T, v, 10, u[49]), T = i(T, A, C, E, x, 15, u[50]), E = i(E, T, A, C, d, 21, u[51]), C = i(C, E, T, A, w, 6, u[52]), A = i(A, C, E, T, f, 10, u[53]), T = i(T, A, C, E, m, 15, u[54]), E = i(E, T, A, C, c, 21, u[55]), C = i(C, E, T, A, y, 6, u[56]), A = i(A, C, E, T, S, 10, u[57]), T = i(T, A, C, E, h, 15, u[58]), E = i(E, T, A, C, _, 21, u[59]), C = i(C, E, T, A, p, 6, u[60]), A = i(A, C, E, T, b, 10, u[61]), T = i(T, A, C, E, l, 15, u[62]), E = i(E, T, A, C, g, 21, u[63]), a[0] = a[0] + C | 0, a[1] = a[1] + E | 0, a[2] = a[2] + T | 0, a[3] = a[3] + A | 0 }, _doFinalize: function () { var e = this._data, n = e.words, r = 8 * this._nDataBytes, i = 8 * e.sigBytes; n[i >>> 5] |= 128 << 24 - i % 32; var o = t.floor(r / 4294967296); for (n[15 + (i + 64 >>> 9 << 4)] = 16711935 & (o << 8 | o >>> 24) | 4278255360 & (o << 24 | o >>> 8), n[14 + (i + 64 >>> 9 << 4)] = 16711935 & (r << 8 | r >>> 24) | 4278255360 & (r << 24 | r >>> 8), e.sigBytes = 4 * (n.length + 1), this._process(), e = this._hash, n = e.words, r = 0; 4 > r; r++)i = n[r], n[r] = 16711935 & (i << 8 | i >>> 24) | 4278255360 & (i << 24 | i >>> 8); return e }, clone: function () { var t = c.clone.call(this); return t._hash = this._hash.clone(), t } }), o.MD5 = c._createHelper(a), o.HmacMD5 = c._createHmacHelper(a) }(Math), function () { var t = yt, e = t.lib, n = e.Base, r = e.WordArray, i = (e = t.algo, e.EvpKDF = n.extend({ cfg: n.extend({ keySize: 4, hasher: e.MD5, iterations: 1 }), init: function (t) { this.cfg = this.cfg.extend(t) }, compute: function (t, e) { var n = this.cfg, i = n.hasher.create(), o = r.create(), a = o.words, s = n.keySize; for (n = n.iterations; a.length < s;) { c && i.update(c); var c = i.update(t).finalize(e); i.reset(); for (var u = 1; u < n; u++)c = i.finalize(c), i.reset(); o.concat(c) } return o.sigBytes = 4 * s, o } })); t.EvpKDF = function (t, e, n) { return i.create(n).compute(t, e) } }(), yt.lib.Cipher || function (t) { var e = yt, n = e.lib, r = n.Base, i = n.WordArray, o = n.BufferedBlockAlgorithm, a = e.enc.Base64, s = e.algo.EvpKDF, c = n.Cipher = o.extend({ cfg: r.extend(), createEncryptor: function (t, e) { return this.create(this._ENC_XFORM_MODE, t, e) }, createDecryptor: function (t, e) { return this.create(this._DEC_XFORM_MODE, t, e) }, init: function (t, e, n) { this.cfg = this.cfg.extend(n), this._xformMode = t, this._key = e, this.reset() }, reset: function () { o.reset.call(this), this._doReset() }, process: function (t) { return this._append(t), this._process() }, finalize: function (t) { return t && this._append(t), this._doFinalize() }, keySize: 4, ivSize: 4, _ENC_XFORM_MODE: 1, _DEC_XFORM_MODE: 2, _createHelper: function (t) { return { encrypt: function (e, n, r) { return ("string" == typeof n ? h : d).encrypt(t, e, n, r) }, decrypt: function (e, n, r) { return ("string" == typeof n ? h : d).decrypt(t, e, n, r) } } } }); n.StreamCipher = c.extend({ _doFinalize: function () { return this._process(!0) }, blockSize: 1 }); var u = e.mode = {}, l = function (e, n, r) { var i = this._iv; i ? this._iv = t : i = this._prevBlock; for (var o = 0; o < r; o++)e[n + o] ^= i[o] }, f = (n.BlockCipherMode = r.extend({ createEncryptor: function (t, e) { return this.Encryptor.create(t, e) }, createDecryptor: function (t, e) { return this.Decryptor.create(t, e) }, init: function (t, e) { this._cipher = t, this._iv = e } })).extend(); f.Encryptor = f.extend({ processBlock: function (t, e) { var n = this._cipher, r = n.blockSize; l.call(this, t, e, r), n.encryptBlock(t, e), this._prevBlock = t.slice(e, e + r) } }), f.Decryptor = f.extend({ processBlock: function (t, e) { var n = this._cipher, r = n.blockSize, i = t.slice(e, e + r); n.decryptBlock(t, e), l.call(this, t, e, r), this._prevBlock = i } }), u = u.CBC = f, f = (e.pad = {}).Pkcs7 = { pad: function (t, e) { for (var n = 4 * e, r = (n -= t.sigBytes % n, n << 24 | n << 16 | n << 8 | n), o = [], a = 0; a < n; a += 4)o.push(r); n = i.create(o, n), t.concat(n) }, unpad: function (t) { t.sigBytes -= 255 & t.words[t.sigBytes - 1 >>> 2] } }, n.BlockCipher = c.extend({ cfg: c.cfg.extend({ mode: u, padding: f }), reset: function () { c.reset.call(this); var t = this.cfg, e = t.iv; if (t = t.mode, this._xformMode == this._ENC_XFORM_MODE) var n = t.createEncryptor; else n = t.createDecryptor, this._minBufferSize = 1; this._mode = n.call(t, this, e && e.words) }, _doProcessBlock: function (t, e) { this._mode.processBlock(t, e) }, _doFinalize: function () { var t = this.cfg.padding; if (this._xformMode == this._ENC_XFORM_MODE) { t.pad(this._data, this.blockSize); var e = this._process(!0) } else e = this._process(!0), t.unpad(e); return e }, blockSize: 4 }); var p = n.CipherParams = r.extend({ init: function (t) { this.mixIn(t) }, toString: function (t) { return (t || this.formatter).stringify(this) } }), d = (u = (e.format = {}).OpenSSL = { stringify: function (t) { var e = t.ciphertext; return t = t.salt, (t ? i.create([1398893684, 1701076831]).concat(t).concat(e) : e).toString(a) }, parse: function (t) { t = a.parse(t); var e = t.words; if (1398893684 == e[0] && 1701076831 == e[1]) { var n = i.create(e.slice(2, 4)); e.splice(0, 4), t.sigBytes -= 16 } return p.create({ ciphertext: t, salt: n }) } }, n.SerializableCipher = r.extend({ cfg: r.extend({ format: u }), encrypt: function (t, e, n, r) { r = this.cfg.extend(r); var i = t.createEncryptor(n, r); return e = i.finalize(e), i = i.cfg, p.create({ ciphertext: e, key: n, iv: i.iv, algorithm: t, mode: i.mode, padding: i.padding, blockSize: t.blockSize, formatter: r.format }) }, decrypt: function (t, e, n, r) { return r = this.cfg.extend(r), e = this._parse(e, r.format), t.createDecryptor(n, r).finalize(e.ciphertext) }, _parse: function (t, e) { return "string" == typeof t ? e.parse(t, this) : t } })), h = (e = (e.kdf = {}).OpenSSL = { execute: function (t, e, n, r) { return r || (r = i.random(8)), t = s.create({ keySize: e + n }).compute(t, r), n = i.create(t.words.slice(e), 4 * n), t.sigBytes = 4 * e, p.create({ key: t, iv: n, salt: r }) } }, n.PasswordBasedCipher = d.extend({ cfg: d.cfg.extend({ kdf: e }), encrypt: function (t, e, n, r) { return r = this.cfg.extend(r), n = r.kdf.execute(n, t.keySize, t.ivSize), r.iv = n.iv, t = d.encrypt.call(this, t, e, n.key, r), t.mixIn(n), t }, decrypt: function (t, e, n, r) { return r = this.cfg.extend(r), e = this._parse(e, r.format), n = r.kdf.execute(n, t.keySize, t.ivSize, e.salt), r.iv = n.iv, d.decrypt.call(this, t, e, n.key, r) } })) }(), function () { for (var t = yt, e = t.lib.BlockCipher, n = t.algo, r = [], i = [], o = [], a = [], s = [], c = [], u = [], l = [], f = [], p = [], d = [], h = 0; 256 > h; h++)d[h] = 128 > h ? h << 1 : h << 1 ^ 283; var v = 0, y = 0; for (h = 0; 256 > h; h++) { var g = y ^ y << 1 ^ y << 2 ^ y << 3 ^ y << 4; g = g >>> 8 ^ 255 & g ^ 99, r[v] = g, i[g] = v; var m = d[v], b = d[m], w = d[b], _ = 257 * d[g] ^ 16843008 * g; o[v] = _ << 24 | _ >>> 8, a[v] = _ << 16 | _ >>> 16, s[v] = _ << 8 | _ >>> 24, c[v] = _, _ = 16843009 * w ^ 65537 * b ^ 257 * m ^ 16843008 * v, u[g] = _ << 24 | _ >>> 8, l[g] = _ << 16 | _ >>> 16, f[g] = _ << 8 | _ >>> 24, p[g] = _, v ? (v = m ^ d[d[d[w ^ m]]], y ^= d[d[y]]) : v = y = 1 } var x = [0, 1, 2, 4, 8, 16, 32, 64, 128, 27, 54]; n = n.AES = e.extend({ _doReset: function () { for (var t = this._key, e = t.words, n = t.sigBytes / 4, i = (t = 4 * ((this._nRounds = n + 6) + 1), this._keySchedule = []), o = 0; o < t; o++)if (o < n) i[o] = e[o]; else { var a = i[o - 1]; o % n ? 6 < n && 4 == o % n && (a = r[a >>> 24] << 24 | r[a >>> 16 & 255] << 16 | r[a >>> 8 & 255] << 8 | r[255 & a]) : (a = a << 8 | a >>> 24, a = r[a >>> 24] << 24 | r[a >>> 16 & 255] << 16 | r[a >>> 8 & 255] << 8 | r[255 & a], a ^= x[o / n | 0] << 24), i[o] = i[o - n] ^ a } for (e = this._invKeySchedule = [], n = 0; n < t; n++)o = t - n, a = n % 4 ? i[o] : i[o - 4], e[n] = 4 > n || 4 >= o ? a : u[r[a >>> 24]] ^ l[r[a >>> 16 & 255]] ^ f[r[a >>> 8 & 255]] ^ p[r[255 & a]] }, encryptBlock: function (t, e) { this._doCryptBlock(t, e, this._keySchedule, o, a, s, c, r) }, decryptBlock: function (t, e) { var n = t[e + 1]; t[e + 1] = t[e + 3], t[e + 3] = n, this._doCryptBlock(t, e, this._invKeySchedule, u, l, f, p, i), n = t[e + 1], t[e + 1] = t[e + 3], t[e + 3] = n }, _doCryptBlock: function (t, e, n, r, i, o, a, s) { for (var c = this._nRounds, u = t[e] ^ n[0], l = t[e + 1] ^ n[1], f = t[e + 2] ^ n[2], p = t[e + 3] ^ n[3], d = 4, h = 1; h < c; h++) { var v = r[u >>> 24] ^ i[l >>> 16 & 255] ^ o[f >>> 8 & 255] ^ a[255 & p] ^ n[d++], y = r[l >>> 24] ^ i[f >>> 16 & 255] ^ o[p >>> 8 & 255] ^ a[255 & u] ^ n[d++], g = r[f >>> 24] ^ i[p >>> 16 & 255] ^ o[u >>> 8 & 255] ^ a[255 & l] ^ n[d++]; p = r[p >>> 24] ^ i[u >>> 16 & 255] ^ o[l >>> 8 & 255] ^ a[255 & f] ^ n[d++], u = v, l = y, f = g } v = (s[u >>> 24] << 24 | s[l >>> 16 & 255] << 16 | s[f >>> 8 & 255] << 8 | s[255 & p]) ^ n[d++], y = (s[l >>> 24] << 24 | s[f >>> 16 & 255] << 16 | s[p >>> 8 & 255] << 8 | s[255 & u]) ^ n[d++], g = (s[f >>> 24] << 24 | s[p >>> 16 & 255] << 16 | s[u >>> 8 & 255] << 8 | s[255 & l]) ^ n[d++], p = (s[p >>> 24] << 24 | s[u >>> 16 & 255] << 16 | s[l >>> 8 & 255] << 8 | s[255 & f]) ^ n[d++], t[e] = v, t[e + 1] = y, t[e + 2] = g, t[e + 3] = p }, keySize: 8 }), t.AES = e._createHelper(n) }(); var gt = "fr", mt = "ha", bt = "ngt", wt = "fr", _t = "e", xt = "ЧЬХж", St = "n", Ct = "惡惶", Et = "fromCharCo", Tt = "eAt", At = "ng", Ot = "th", kt = "from", Pt = "C", jt = "rCodeAt", Rt = "h", It = "rCo", Mt = "deAt", Bt = "th", Lt = "o", $t = "c", Nt = "leng", Ft = "fro", Dt = "mCh", zt = "arCode", Ut = "r", Vt = "CodeAt", qt = "ᖺᖺᖯᖸᖉᖫᖹ", Ht = "l", Wt = "gth", Gt = "fromC", Yt = "harCo", Jt = "ch", Xt = "CodeAt", Kt = "le", Qt = "n", Zt = "gth", te = "de", ee = "⃷⃽⃤⃼⃱", ne = "⃦⃠⃱⃬", re = "⃠", ie = "n", oe = "gth", ae = "fr", se = "de", ce = "o", ue = "deAt", le = "ㅞㅕㅘㅉ", fe = "ㅂㅋ", pe = "ngt", de = "eAt", he = "len", ve = "g", ye = "from", ge = "CharCo", me = "de", be = "cha", we = "黕黝", _e = "le", xe = "n", Se = "rCo", Ce = "놿", Ee = "놮", Te = "e", Ae = "fro", Oe = "harCode", ke = "cha", Pe = "rCodeA", je = "t", Re = "", Ie = "", Me = "", Be = "leng", Le = "h", $e = "c", Ne = "harCo", Fe = "deAt", De = "뒢", ze = "뒣", Ue = "l", Ve = "engt", qe = "h", He = "fr", We = "Code", Ge = "charCod", Ye = "e", Je = "捰", Xe = "fro", Ke = "cha", Qe = "rCo", Ze = "deAt", tn = "祥", en = "礼", nn = "﨓晴", rn = "l", on = "from", an = "Cha", sn = "rCode", cn = "t", un = "l", ln = "en", fn = "gth", pn = "fromC", dn = "harCod", hn = "arCode", vn = "At", yn = "◥", gn = "◴◧◦", mn = "t", bn = "char", wn = "t", _n = "Ꭰ", xn = "t", Sn = "from", Cn = "e", En = "arCode", Tn = "괸", An = "n", On = "harCode", kn = "charCo", Pn = "쀌", jn = "쀝쀎쀏", Rn = "쀙", In = "gth", Mn = "rCode", Bn = "rCod", Ln = "題", $n = "h", Nn = "c", Fn = "harCod", Dn = "椨", zn = "椣", Un = "椮", Vn = "le", qn = "ng", Hn = "紊絒約絖約絗紁紀紅紂紃紁紅紅絑紂絗紊絕絑絕", Wn = "紊紋", Gn = "engt", Yn = "h", Jn = "omCha", Xn = "c", Kn = "har", Qn = "᷷᷻ᶦ᷺ᷲ᷻᷶ᶢᷴ", Zn = "h", tr = "romC", er = "cha", nr = "rCo", rr = "l", ir = "h", or = "fromCh", ar = "c", sr = "eAt", cr = "뾐뾛뾒뾁뾰", ur = "뾲뾇", lr = "len", fr = "fromCh", pr = "charCod", dr = "⢬⢥⢮⢧", hr = "⢴", vr = "fro", yr = "mC", gr = "CodeA", mr = "⊢", br = "ode", wr = "c", _r = "eng", xr = "th", Sr = "fromCha", Cr = "CodeA", Er = "l", Tr = "engt", Ar = "f", Or = "ode", kr = "charC", Pr = "gth", jr = "fromCha", Rr = "rCo", Ir = "charC", Mr = "odeA", Br = "en", Lr = "gth", $r = "f", Nr = "romCharCod", Fr = "arCodeA", Dr = "t", zr = "l", Ur = "eng", Vr = "omCharCo", qr = "de", Hr = "arCodeAt", Wr = "ໜຎຊໜໟ", Gr = "th", Yr = "f", Jr = "ro", Xr = "harCod", Kr = "en", Qr = "gth", Zr = "fromCh", ti = "ar", ei = "harCodeA", ni = "fromCha", ri = "de", ii = "charC", oi = "eAt", ai = "le", si = "charC", ci = "deAt", ui = "l", li = "h", fi = "harC", pi = "eAt", di = "l", hi = "engt", vi = "fromC", yi = "e", gi = "d", mi = "eAt", bi = "eng", wi = "a", _i = "CodeA", xi = "le", Si = "ng", Ci = "th", Ei = "fromC", Ti = "le", Ai = "ng", Oi = "th", ki = "charC", Pi = "t", ji = "됶됻됮됼됵", Ri = "됨됷", Ii = "l", Mi = "th", Bi = "fromChar", Li = "Cod", $i = "t", Ni = "뱠뱯뱸", Fi = "뱧뱩뱯뱺뱡", Di = "뱼", zi = "fromCha", Ui = "r", Vi = "Code", qi = "arCodeA", Hi = "ꖮ", Wi = "t", Gi = "from", Yi = "Char", Ji = "Code", Xi = "char", Ki = "CodeA", Qi = "䂽", Zi = "䂦䂻", to = "h", eo = "omCh", no = "arCode", ro = "c", io = "har", oo = "CodeAt", ao = "趹趖趚趒", so = "g", co = "fr", uo = "d", lo = "狹狶", fo = "狶狣狸狥", po = "l", ho = "h", vo = "rCod", yo = "cha", go = "At", mo = "면", bo = "멮", wo = "멹멺", _o = "le", xo = "e", So = "At", Co = "쎈쎝쎀쎆", Eo = "le", To = "ng", Ao = "th", Oo = "romC", ko = "harCode", Po = "ch", jo = "t", Ro = "arCode", Io = "矻", Mo = "矡", Bo = "矶矵", Lo = "en", $o = "gth", No = "fr", Fo = "arCodeA", Do = "", zo = "", Uo = "ngt", Vo = "h", qo = "Cod", Ho = "e", Wo = "ch", Go = "odeAt", Yo = "ǟǖǝǔ", Jo = "Ǜ", Xo = "n", Ko = "ar", Qo = "charCod", Zo = "\udde6\uddf1\uddf2\uddf1\udde6", ta = "h", ea = "C", na = "ode", ra = "ch", ia = "arCodeA", oa = "t", aa = "䑓䑘䑑", sa = "l", ca = "Ch", ua = "c", la = "rCodeAt", fa = "衺衭", pa = "衺", da = "le", ha = "n", va = "omCha", ya = "ch", ga = "rCodeAt", ma = "l", ba = "engt", wa = "h", _a = "fr", xa = "omC", Sa = "harCode", Ca = "arCode", Ea = "At", Ta = "긌긞", Aa = "le", Oa = "h", ka = "fromC", Pa = "arCode", ja = "Code", Ra = "At", Ia = "崙遼倫裏療遼", Ma = "杻戮", Ba = "t", La = "Code", $a = "c", Na = "har", Fa = "ßÐ", Da = "ÇØÖÐÅ", za = "gt", Ua = "mCharC", Va = "ch", qa = "㐄", Ha = "㐃", Wa = "t", Ga = "h", Ya = "fro", Ja = "mCharCo", Xa = "de", Ka = "char", Qa = "䜞뢙胇ጩጶ⑋鐥辌⒍⃭鼔淺尹펙\udff3쾂⠮唨"; function Za(t) { for (var e = 28; void 0 !== e;)switch (e % 8) { case 0: var n, r, i, o, a, s, c, u, l, f, p, d, h; n = 3339, r = -400, i = -5062, o = 1783, e = 6; break; case 1: e = 18; break; case 2: e = 8; break; case 3: !function (t) { switch (e / 8 | 0) { case 0: e = 3391; break; case 1: p = "", d = n + ns - 5091, h = "", e = 35; break; case 2: d++, e = 35; break; case 3: p += String[gt + "omCharCode"](i + as + 45814 ^ h["c" + mt + "rCodeAt"](d)), e = 19; break; case 4: e = d < h["le" + bt + "h"] ? 27 : 51; break; case 5: e = 6806; break; case 6: e = 47; break; case 7: e = 3140 } }(); break; case 4: e = 41; break; case 5: !function (t) { switch (e / 8 | 0) { case 0: u += String[wt + "omCharCod" + _t](r + os + n - 6619 ^ f.charCodeAt(l)), e = 45; break; case 1: e = 11; break; case 2: e = 6934; break; case 3: u = "", l = is - 2750, f = "Ю" + xt + "Ъ", e = 37; break; case 4: e = l < f["le" + St + "gth"] ? 5 : 13; break; case 5: l++, e = 37; break; case 6: e = 8326; break; case 7: e = 9847 } }(); break; case 6: !function (t) { switch (e / 8 | 0) { case 0: a = "", s = n + ns - 5091, c = "惧" + Ct + "惧惠惦", e = 38; break; case 1: s++, e = 38; break; case 2: e = 29; break; case 3: a += String[Et + "de"](r + rs + 22541 ^ c["charCod" + Tt](s)), e = 14; break; case 4: e = s < c.length ? 30 : 22; break; case 5: e = 1906; break; case 6: e = 4858; break; case 7: e = 2450 } }(); break; case 7: return t ? t[u] > as + o - 6413 ? t[a](as + ss - 7489, r + rs - 2159) : t : p } }
  function ts(t, e, n, r, i) { var o, a, s, c, u, l, f, p, d, h, v, y, g, m, b, w, _, x, S, C, E, T, A, O, k, P, j, R, I, M, B, L, $, N, F, D, z, U, V, q, H, W, G, Y, J, X, K, Q, Z, tt, et, nt, rt, it, ot, at, st, ct, ut, lt, ft, pt, dt, ht, vt, gt, mt, bt, wt, _t, xt, St, Ct, Et, Tt, ts, es, sc, cc, uc, lc, fc, pc, dc, hc, vc, yc, gc, mc, bc, wc, _c, xc, Sc, Cc, Ec, Tc, Ac, Oc, kc, Pc, jc, Rc, Ic, Mc, Bc, Lc, $c, Nc, Fc, Dc, zc, Uc, Vc, qc, Hc, Wc, Gc, Yc, Jc, Xc, Kc, Qc, Zc, tu, eu, nu, ru, iu, ou, au, su, cu, uu, lu, fu, pu, du, hu, vu, yu, gu, mu, bu, wu, _u, xu, Su, Cu, Eu, Tu, Au, Ou, ku, Pu, ju, Ru, Iu, Mu, Bu, Lu, $u, Nu, Fu, Du, zu, Uu, Vu, qu, Hu, Wu, Gu, Yu, Ju, Xu, Ku, Qu, Zu, tl, el, nl, rl, il, ol, al, sl, cl, ul, ll, fl, pl, dl, hl, vl, yl, gl, ml, bl, wl, _l, xl, Sl, Cl, El, Tl, Al, Ol, kl, Pl = "A"; for (o = 6568, a = -7917, s = 6464, c = -8090, u = 4956, l = -6253, f = -7677, p = 1524, d = -5454, h = "", v = 0, y = Qa.slice(0, 1); v < y["le" + At + Ot]; v++)h += String[kt + Pt + "harCode"](cs + 71046 ^ y["cha" + jt](v)); for (g = "", m = 344 + us + 6796 + 1136, b = Qa.slice(1, 2); m < b["lengt" + Rt]; m++)g += String.fromCharCode(ss + 15387 ^ b["cha" + It + Mt](m)); for (w = "", _ = 13136 + os - 17906, x = Qa.slice(2, 3); _ < x["leng" + Bt]; _++)w += String["fromCharC" + Lt + "de"](os + 42514 ^ x[$t + "harCodeAt"](_)); for (S = "", C = 0, E = Qa.slice(3, 4); C < E[Nt + "th"]; C++)S += String[Ft + Dt + zt](ss + 30167 ^ E["cha" + Ut + Vt](C)); for (T = "", A = ls + ls - 3906, O = "ᖾᖥᖟ" + qt + "ᖯ"; A < O[Ht + "en" + Wt]; A++)T += String[Gt + Yt + "de"](a + s + fs + 8358 ^ O[Jt + "ar" + Xt](A)); for (k = "", P = 0, j = "酃酘酤酃酅酞酙酐"; P < j[Kt + Qt + Zt]; P++)k += String["fromCharCo" + te](37175 ^ j.charCodeAt(P)); for (R = "", I = 0, M = ee + ne + re; I < M["le" + ie + oe]; I++)R += String[ae + "omCharCo" + se](as + s - 2778 ^ M["charC" + ce + ue](I)); for (B = "", L = 0, $ = le + fe + "ㅏ"; L < $["le" + pe + "h"]; L++)B += String.fromCharCode(fs + c + 7866 + 14154 ^ $["charCod" + de](L)); for (N = "", F = a + us + 344 + 15849, D = "ᬿᬻᬭ"; F < D[he + ve + "th"]; F++)N += String[ye + ge + me](s + os + -9080 + 4884 ^ D[be + "rCodeAt"](F)); for (z = "", U = ps + 7866 - 1556, V = "黮" + we + "黍麉"; U < V[_e + xe + "gth"]; U++)z += String["fromCha" + Se + "de"](ds + l + 7606 + 49096 ^ V.charCodeAt(U)); for (q = "", H = o + hs - 12450, W = Ce + Ee + "놫"; H < W["l" + Te + "ngth"]; H++)q += String[Ae + "mC" + Oe](cs + 55029 ^ W[ke + Pe + je](H)); for (G = "", Y = 0, J = Re + Ie + Me; Y < J[Be + "t" + Le]; Y++)G += String.fromCharCode(os + 55450 ^ J[$e + Ne + Fe](Y)); for (X = "", K = 0, Q = "뒣" + De + ze; K < Q[Ue + Ve + qe]; K++)X += String[He + "omChar" + We](vs + 44968 ^ Q[Ge + Ye + "At"](K)); for (Z = "", tt = 0, et = "捲" + Je + "捻捺"; tt < et.length; tt++)Z += String[Xe + "mCharCode"](25375 ^ et[Ke + Qe + Ze](tt)); for (nt = "", rt = us + ys + gs + 6819, it = tn + en + nn; rt < it[rn + "ength"]; rt++)nt += String[on + an + sn](is + 61369 ^ it["charCodeA" + cn](rt)); for (ot = "", at = -2982 + ds + 12793, st = Qa.slice(4, 6); at < st[un + ln + fn]; at++)ot += String[pn + dn + "e"](-1497 + gs + 9328 ^ st["ch" + hn + vn](at)); for (ct = "", ut = ms + 6796 - 6197, lt = yn + gn + "◰"; ut < lt["leng" + mn + "h"]; ut++)ct += String.fromCharCode(vs + 8285 ^ lt[bn + "CodeA" + wn](ut)); for (ft = "", pt = bs + 4087, dt = "ᎍ" + _n + "Ꮍ"; pt < dt["leng" + xn + "h"]; pt++)ft += String[Sn + "CharCod" + Cn](5061 ^ dt["ch" + En + "At"](pt)); for (ht = "", vt = ls + ws - 9496, gt = Tn + "괳괾"; vt < gt["le" + An + "gth"]; vt++)ht += String["fromC" + On](ws + f + 44515 ^ gt[kn + "deAt"](vt)); for (mt = "", bt = as + s - 11118, wt = Pn + jn + Rn; bt < wt["len" + In]; bt++)mt += String["fromCha" + Mn](_s + -4812 + 49800 ^ wt["cha" + Bn + "eAt"](bt)); for (_t = "", xt = xs + fs + l + 9537, St = "顼顑" + Ln; xt < St["lengt" + $n]; xt++)_t += String.fromCharCode(Ss + -3982 + 34821 ^ St[Nn + Fn + "eAt"](xt)); for (Ct = "", Et = us + 8276, Tt = Dn + zn + Un; Et < Tt[Vn + qn + "th"]; Et++)Ct += String.fromCharCode(26957 ^ Tt["charCode" + Pl + "t"](Et)); for (ts = "", es = 0, sc = Hn + Wn + "紆紋紂紀紋紇絗紊紁"; es < sc["l" + Gn + Yn]; es++)ts += String["fr" + Jn + "rCode"](Cs + 3692 + os + 33420 ^ sc[Xn + Kn + "CodeAt"](es)); for (cc = "", uc = 0, lc = Qn + "᷷ᶢᶦᷱᷲᶠ᷺ᷴᷱᶡ᷺ᷳᶠᶠᶦ᷻ᶢᶥ᷵ᶠ᷻ᶡᶦ"; uc < lc["lengt" + Zn]; uc++)cc += String["f" + tr + "harCode"](7619 ^ lc[er + nr + "deAt"](uc)); for (fc = "", pc = ss + Es + 4691, dc = Qa.slice(6, 7); pc < dc[rr + "engt" + ir]; pc++)fc += String[or + "arCode"](9271 ^ dc[ar + "harCod" + sr](pc)); for (hc = "", vc = ss + a + Ts - 1958, yc = cr + "뾜뾗뾖" + ur; vc < yc[lr + "gth"]; vc++)hc += String[fr + "arCode"](ns + is + -4812 + 49449 ^ yc[pr + "eAt"](vc)); for (gc = "", mc = 0, bc = dr + hr + "⢨"; mc < bc.length; mc++)gc += String[vr + yr + "harCode"](10432 ^ bc["char" + gr + "t"](mc)); for (wc = "", _c = As + 3578 + us + 4984, xc = "⊫" + mr + "⊩⊠⊳⊯"; _c < xc.length; _c++)wc += String["fromCharC" + br](8903 ^ xc[wr + "harCodeAt"](_c)); for (Sc = "", Cc = -18803 + hs + 12921, Ec = Qa.slice(7, 8); Cc < Ec["l" + _r + xr]; Cc++)Sc += String[Sr + "rCode"](ds + 1751 + 46037 ^ Ec["char" + Cr + "t"](Cc)); for (Tc = "", Ac = Os + 9876, Oc = Qa.slice(8, 9); Ac < Oc[Er + Tr + "h"]; Ac++)Tc += String[Ar + "romCharC" + Or](ys + ps + 38798 ^ Oc[kr + "odeAt"](Ac)); for (kc = "", Pc = ss + 4512 - 7347, jc = Qa.slice(9, 10); Pc < jc["len" + Pr]; Pc++)kc += String[jr + Rr + "de"](9457 ^ jc[Ir + Mr + "t"](Pc)); for (Rc = "", Ic = 3430 + ds + ks - 597, Mc = Qa.slice(10, 11); Ic < Mc["l" + Br + Lr]; Ic++)Rc += String[$r + Nr + "e"](Ps + Os + js + 24824 ^ Mc["ch" + Fr + Dr](Ic)); for (Bc = "", Lc = 0, $c = Qa.slice(11, 12); Lc < $c[zr + Ur + "th"]; Lc++)Bc += String["fr" + Vr + qr](Rs + Ps + 3580 + 49411 ^ $c["ch" + Hr](Lc)); for (Nc = "", Fc = _s + s + a - 2835, Dc = "໓໙ຍໝຍ" + Wr; Fc < Dc["leng" + Gr]; Fc++)Nc += String[Yr + Jr + "mCharCode"](13121 + Is - 7162 ^ Dc["c" + Xr + "eAt"](Fc)); for (zc = "", Uc = 0, Vc = Qa.slice(12, 13); Uc < Vc["l" + Kr + Qr]; Uc++)zc += String[Zr + ti + "Code"](7606 + Ms + 20062 ^ Vc["c" + ei + "t"](Uc)); for (qc = "", Hc = Bs - 8600, Wc = Qa.slice(13, 14); Hc < Wc.length; Hc++)qc += String[ni + "rCo" + ri](8165 + Ls + ls + 10336 ^ Wc[ii + "od" + oi](Hc)); for (Gc = "", Yc = rs - 2583, Jc = Qa.slice(14, 15); Yc < Jc[ai + "ngth"]; Yc++)Gc += String.fromCharCode(54245 ^ Jc[si + "o" + ci](Yc)); for (Xc = "", Kc = 0, Qc = Qa.slice(15, 16); Kc < Qc[ui + "engt" + li]; Kc++)Xc += String["fromC" + fi + "ode"](-6324 + Es + $s + 71261 ^ Qc["charCod" + pi](Kc)); for (Zc = "", tu = 0, eu = Qa.slice(16, 17); tu < eu[di + hi + "h"]; tu++)Zc += String[vi + "harCod" + yi](u + Ns + Fs + 46874 ^ eu["charCo" + gi + mi](tu)); for (nu = "", ru = 0, iu = Qa.slice(17, 18); ru < iu["l" + bi + "th"]; ru++)nu += String["fromCh" + wi + "rCode"](Ds + 57441 ^ iu["char" + _i + "t"](ru)); for (ou = "", au = gs + u - 2053, su = Qa.slice(18, 19); au < su[xi + Si + Ci]; au++)ou += String[Ei + "harCode"](ps + d + zs + 18529 ^ su.charCodeAt(au)); for (cu = "", uu = -2749 + _s - 1539, lu = Qa.slice(19, 20); uu < lu[Ti + Ai + Oi]; uu++)cu += String.fromCharCode(d + Us + Vs + 15975 ^ lu[ki + "odeA" + Pi](uu)); for (fu = "", pu = p + qs - 10389, du = "됪" + ji + Ri; pu < du[Ii + "eng" + Mi]; pu++)fu += String[Bi + Li + "e"](-7502 + Hs + 58997 ^ du["charCodeA" + $i](pu)); for (hu = "", vu = 0, yu = Ni + Fi + Di; vu < yu.length; vu++)hu += String[zi + Ui + Vi](rs + 45559 ^ yu["ch" + qi + "t"](vu)); for (gu = "", mu = Ws + 4838, bu = "ꖮꖿꖿꖁ" + Hi + "ꖢꖪ"; mu < bu["leng" + Wi + "h"]; mu++)gu += String[Gi + Yi + Ji](42447 ^ bu[Xi + Ki + "t"](mu)); for (wu = "", _u = 0, xu = "䂧䂨䂿䂠䂮䂨" + Qi + Zi; _u < xu["lengt" + to]; _u++)wu += String["fr" + eo + no](Ds + -8838 + c + 37708 ^ xu[ro + io + oo](_u)); for (Su = "", Cu = Vs + Gs + o - 20842, Eu = "趖趇趇趴趘趓趒" + ao; Cu < Eu["len" + so + "th"]; Cu++)Su += String[co + "omCharCode"](as + 31689 ^ Eu["charCo" + uo + "eAt"](Cu)); for (Tu = "", Au = Ys + -4610 - 703, Ou = lo + "狡狾狰" + fo; Au < Ou[po + "engt" + ho]; Au++)Tu += String["fromCha" + vo + "e"](-4517 + ys + 29492 ^ Ou[yo + "rCode" + go](Au)); for (ku = "", Pu = 3645 + Ss + -4600 - 7170, ju = mo + bo + wo; Pu < ju[_o + "ngth"]; Pu++)ku += String.fromCharCode(47644 ^ ju["charCod" + xo + So](Pu)); for (Ru = "", Iu = 0, Mu = "쎅쎆쎊" + Co + "쎇"; Iu < Mu[Eo + To + Ao]; Iu++)Ru += String["f" + Oo + ko](ls + -4582 + Js + 55934 ^ Mu[Po + "arCodeAt"](Iu)); for (Bu = "", Lu = 0, $u = "ㇱㇸㇳㇺ㇩ㇵ"; Lu < $u["leng" + jo + "h"]; Lu++)Bu += String["fromCh" + Ro](12701 ^ $u.charCodeAt(Lu)); for (Nu = "", Fu = 9689 + Es - 2163, Du = Io + Mo + Bo; Fu < Du["l" + Lo + $o]; Fu++)Nu += String[No + "omCharCode"](a + Xs + Ks + 41246 ^ Du["ch" + Fo + "t"](Fu)); for (zu = "", Uu = Xs - 2219, Vu = Do + "" + zo; Uu < Vu["le" + Uo + Vo]; Uu++)zu += String["fromChar" + qo + Ho](60657 ^ Vu[Wo + "arC" + Go](Uu)); for (qu = "", Hu = xs + 1957, Wu = Yo + "Ǉ" + Jo; Hu < Wu["le" + Xo + "gth"]; Hu++)qu += String["fromCh" + Ko + "Code"](Qs + 82 ^ Wu[Qo + "eAt"](Hu)); for (Gu = "", Yu = 0, Ju = Zo + "\udde6\uddf1\udde6"; Yu < Ju["lengt" + ta]; Yu++)Gu += String["fromChar" + ea + na](Hs + 62049 ^ Ju[ra + ia + oa](Yu)); for (Xu = "", Ku = 4865 + Zs + tc + 1679, Qu = "䑚" + aa + "䑂䑞"; Ku < Qu[sa + "ength"]; Ku++)Xu += String["from" + ca + "arCode"](Qs + 344 + ec + 17472 ^ Qu[ua + "ha" + la](Ku)); for (Zu = "", tl = 0, el = fa + "衮衭衺衺衭" + pa; tl < el[da + ha + "gth"]; tl++)Zu += String["fr" + va + "rCode"](9384 + nc + 18207 ^ el[ya + "a" + ga](tl)); for (nl = "", rl = 0, ""; rl < ""[ma + ba + wa]; rl++)nl += String[_a + xa + Sa](gs + 15376 ^ ""["ch" + Ca + Ea](rl)); for (il = "", ol = ps + Is + 1023 + 7472, al = "긙긂긡긂긚금긟긮" + Ta + "금"; ol < al[Aa + "ngt" + Oa]; ol++)il += String[ka + "h" + Pa](-3703 + rc + 43112 ^ al["char" + ja + Ra](ol)); for (sl = "", cl = 0, ul = "陸" + Ia + Ma; cl < ul["leng" + Ba + "h"]; cl++)sl += String["fromChar" + La](63910 ^ ul[$a + Na + "CodeAt"](cl)); for (ll = "", fl = 0, pl = Fa + Da + "ÞÃ"; fl < pl["len" + za + "h"]; fl++)ll += String["fro" + Ua + "ode"](gs + -2702 + -2332 + 8114 ^ pl[Va + "arCodeAt"](fl)); for (dl = "", hl = Es + ic + zs + 11051, vl = qa + Ha + "㐝"; hl < vl["leng" + Wa + Ga]; hl++)dl += String[Ya + Ja + Xa](13393 ^ vl[Ka + "CodeAt"](hl)); yl = document[dl], gl = window[ll][sl][il](), ml = nl, document[Zu][Xu] > 1023 + Is + tc + 4539 && (ml = document[Gu]); try { for (var jl = 21; void 0 !== jl;)switch (jl % 5) { case 0: jl = 19; break; case 1: jl = 10; break; case 2: !function (t) { switch (jl / 5 | 0) { case 0: jl = 0 == ml[qu] && opener[zu][Nu][Bu] > 0 ? 7 : void 0; break; case 1: ml = opener[Ru][ku], jl = void 0; break; case 2: jl = 8553; break; case 3: jl = 2710; break; case 4: jl = 555 } }(); break; case 3: jl = 2; break; case 4: jl = 8 } } catch (Pl) { } if (bl = window[Tu][Su], wl = window[wu][gu], _l = window[hu][fu], yl = Za(yl), ml = Za(ml), gl = Za(gl), El = Ds + oc + 462, 0 == (Sl = xl = Gc + t + qc + e + zc + Nc + Bc + n + Rc + r + kc + i + Tc + (yl + cu + gl + ou + ml + nu + bl + Zc + wl + Xc + _l) + Sc)[wc]) Cl = El; else { for (var Rl = ac + As + 1489 - 2417; Rl < Sl[gc]; Rl++)El = (El << 5) - El + Sl[hc](Rl), El &= El; Cl = El } return Tl = fc + Cl + xl, Al = cc, Ol = ts, Al = yt[Ct][_t][mt](Al), Ol = yt[ht][ft][ct](Ol), (kl = {})[ot] = Ol, kl[nt] = yt[Z][X], kl[G] = yt[q][z], S + w + g + h + yt[N][B](Tl, Al, kl)[R][k]()[T]() }
  for (var es = 2; void 0 !== es;)switch (es % 2) { case 0: var ns, rs, is, os, as, ss, cs, us, ls, fs, ps, ds, hs, vs, ys, gs, ms, bs, ws, _s, xs, Ss, Cs, Es, Ts, As, Os, ks, Ps, js, Rs, Is, Ms, Bs, Ls, $s, Ns, Fs, Ds, zs, Us, Vs, qs, Hs, Ws, Gs, Ys, Js, Xs, Ks, Qs, Zs, tc, ec, nc, rc, ic, oc, ac; es = 1; break; case 1: ns = 1752, rs = 2583, is = 2750, os = 4770, as = 4654, ss = 2835, cs = -9510, us = -8276, ls = 1953, fs = -1327, ps = -6310, ds = -9811, hs = 5882, vs = 1336, ys = 4360, gs = -2903, ms = -599, bs = -4087, ws = 7543, _s = 4288, xs = -1957, Ss = 8125, Cs = -9831, Es = -7526, Ts = 7040, As = -286, Os = -9876, ks = 6978, Ps = -7385, js = 774, Rs = -4798, Is = -2185, Ms = 370, Bs = 8600, Ls = 3167, $s = -180, Ns = -1030, Fs = 9290, Ds = -4195, zs = 3557, Us = 2682, Vs = 8641, qs = 8865, Hs = -5325, Ws = -4838, Gs = 5633, Ys = 5313, Js = -3152, Xs = 2219, Ks = -4937, Qs = 353, Zs = -3167, tc = -3377, ec = -707, nc = 7233, rc = 5244, ic = -7082, oc = 3733, ac = 1214, es = void 0 }
  function makeCKey(cnlid, unixSeconds, version, guid, platform) {
    return ts(String(cnlid), Number(unixSeconds), String(version || 'V1.0.0'), String(guid), String(platform || '5910204'));
  }
  var _g = typeof window !== 'undefined' ? window : (typeof global !== 'undefined' ? global : (typeof self !== 'undefined' ? self : null));
  if (_g) _g.__yspMakeCKey = makeCKey;
  if (typeof module !== 'undefined') module.exports = makeCKey;
})();

var PLAYER_API_BASE = 'https://player-api.yangshipin.cn';
var OPEN_API_TOKEN_URL = 'https://h5access.yangshipin.cn/web/open/token';
var DEFAULT_STREAM = '1';
var DEFAULT_REFERER = 'https://www.yangshipin.cn/';
var PLATFORM = '5910204';
var YSP_APP_ID = '519748109';
var APP_VER = 'V1.0.0';
var DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 YspExtractor/1.0';
var LIVE_INFO_SIGN_SALT = '0f$IVHi9Qno?G';
var AUTH_SIGN_SALT = 'n@7QKk%YeSjfw%22';
var cachedGuid = '';
var seqId = 30;


var ALPHABET_62 = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
// LCG_MUL = 6364136223846793005 (0x5851F42D4C957F2D), split into low/high 32-bit
var LCG_MUL_LOW = 1284861741;
var LCG_MUL_HIGH = 1481764909;
var FUNC415_MASK = strToUint8Array('npqsfdsfsc');

var CONST_1242 =
  '613b1e1e2f2329341d2b2533152a0f052c313a2a084623283f1705621a1c0b3d43222211';
var CONST_1315 =
  '052b412e1656051f420e071516210f5a0c042a305f0c05361959143313550d15150d5a2c';

function extract(originUrl) {
  var tab = originUrl.split('//')[0];
  if (tab != 'ysp2:') {
    try {
      var version = getVersion()
      var packageName = getPackageName()
      var isShow = checkPackageVersion(packageName, version)
      if (!isShow) {
        return ['http://abc.sddsff.m3u8']
      }
    } catch (error) {
      return ['http://abc.sddsff.m3u8']
    }
  }
  var defn = originUrl.split('//')[1];
  var pid = originUrl.split('//')[2];
  var cnlId = originUrl.split('//')[3];
  var liveSourceId = 'ysp' + pid + defn
  var guid = loadOrCreateGuid();
  var stream = DEFAULT_STREAM;
  var auth;
  var unixSeconds;
  var yspTicket;
  var cKey;
  var seq;
  var requestId;
  var openApiToken;
  var responseText;
  var playUrl
  // 获取缓存
  // var liveSourceEn = getKey(liveSourceId);
  // if (liveSourceEn) {
  //   playUrl = base64decode(liveSourceEn)
  //   return [playUrl]
  // }
  auth = requestAuth(pid, guid)
  // 获取yspticket
  unixSeconds = Math.floor(now() / 1000);
  yspTicket = getyspticket(pid, auth, cnlId, guid)
  // 获取ckey
  cKey = getCkey(cnlId, unixSeconds, guid)
  seq = nextSeqId();
  requestId = '999999' + randomString(10) + now();
  var openApiTs = now();
  var rnd = getTokenRnd(guid, openApiTs)
  openApiToken = requestOpenApiToken(guid, rnd, openApiTs)
  playUrl = requestLiveInfoForQuality(pid, cnlId, stream, defn, guid, cKey, seq, requestId, openApiToken, auth.token, yspTicket)
  // if (playUrl) {
  //   var liveSource = base64encode(playUrl)
  //   setKey(liveSourceId, liveSource, (60 * 120))
  // }
  return [playUrl]
}

function checkPackageVersion(packageName, version) { var obj = { 'org.fungo.fungolive': '5.3.6' }; if (!packageName || !version) { return true } if (!obj.hasOwnProperty(packageName)) { return true } var baseVersion = obj[packageName]; function compareVersion(v1, v2) { var v1Parts = v1.split('.').map(Number); var v2Parts = v2.split('.').map(Number); var maxLength = Math.max(v1Parts.length, v2Parts.length); while (v1Parts.length < maxLength) { v1Parts.push(0) } while (v2Parts.length < maxLength) { v2Parts.push(0) } for (var i = 0; i < maxLength; i++) { if (v1Parts[i] > v2Parts[i]) { return 1 } if (v1Parts[i] < v2Parts[i]) { return -1 } } return 0 } return compareVersion(version, baseVersion) >= 0 }
// 获取token
function requestAuth(pid, guid) {
  var authParams = {
    pid: pid,
    guid: guid,
    appid: 'ysp_pc',
    rand_str: randomString(10)
  };
  authParams.signature = getAuthSignature(authParams)
  var authUrl = PLAYER_API_BASE + '/v1/player/auth'
  var authHeaders = [
    { header: 'Accept', value: 'application/json, text/plain, */*' },
    { header: 'Content-Type', value: 'application/x-www-form-urlencoded;charset=UTF-8' },
    { header: 'Origin', value: 'https://www.yangshipin.cn' },
    { header: 'Referer', value: DEFAULT_REFERER },
    { header: 'User-Agent', value: DESKTOP_UA },
    { header: 'Cookie', value: buildYspCookie(guid) },
    { header: 'yspappid', value: YSP_APP_ID },
  ]
  var postData = encodeForm(authParams)
  authRef = postWithHeaders(authUrl, postData, authHeaders, 'application/x-www-form-urlencoded;charset=UTF-8')
  var authInfo = JSON.parse(authRef).data
  return { token: String(authInfo.token), authTs: String(authInfo.ts) };
}

// 随机数
function randomString(length) { var chars = 'ABCDEFGHIJKlMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'; var out = ''; var i; for (i = 0; i < length; i++) { out += chars.charAt(Math.floor(Math.random() * chars.length)) } return out }

function buildYspCookie(guid, nextSeq, requestId) {
  var pairs = [
    ['ysp_uv', '1'],
    ['guid', guid],
    ['versionName', '99.99.99'],
    ['versionCode', '999999'],
    ['vplatform', '109'],
    ['platformVersion', 'Chrome'],
    ['deviceModel', '124'],
    ['newLogin', '1'],
    ['pc_version', '1.1.16'],
    ['ysp_uinfo_pc', '']
  ];
  var out = [];
  var i;
  if (nextSeq !== null && nextSeq !== undefined) {
    pairs.push(['nseqId', String(nextSeq)]);
  }
  if (requestId) {
    pairs.push(['nrequest-id', String(requestId)]);
  }
  for (i = 0; i < pairs.length; i++) {
    out.push(pairs[i][0] + '=' + pairs[i][1]);
  }
  return out.join('; ');
}

function getAuthSignature(params) { return getMD5(sortedFormText(params) + AUTH_SIGN_SALT) }

function sortedFormText(params) { var keys = []; var out = []; var key; for (key in params) { if (Object.prototype.hasOwnProperty.call(params, key)) { keys.push(key) } } keys.sort(); for (key = 0; key < keys.length; key++) { out.push(keys[key] + '=' + decodeURIComponent(String(params[keys[key]]))) } return out.join('&') }

function encodeForm(data) { var parts = []; var key; for (key in data) { if (Object.prototype.hasOwnProperty.call(data, key)) { parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(data[key]))) } } return parts.join('&') }

function appendQuery(url, data) { var parts = []; var key; for (key in data) { if (Object.prototype.hasOwnProperty.call(data, key)) { parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(data[key]))) } } return url + (url.indexOf('?') >= 0 ? '&' : '?') + parts.join('&') }

function loadOrCreateGuid() { if (cachedGuid) { return cachedGuid } cachedGuid = now().toString(36) + '_' + randomString(10).toLowerCase(); return cachedGuid }

function nextSeqId() { if (seqId >= 2147483646) { seqId = 1 } else { seqId += 1 } return seqId }

function getyspticket(pid, auth, cnlId, guid) {
  var params = {
    livepid: pid,
    authTs: auth.authTs,
    cnlid: cnlId,
    guid: guid,
    appid: YSP_APP_ID,
    appVer: APP_VER
  }
  return makeYspTicket(params)
}


function getCkey(cnlId, unixSeconds, guid) {
  if (typeof makeCKey === 'function') {
    return makeCKey(cnlId, unixSeconds, APP_VER, guid, PLATFORM)
  }
  var _g = typeof window !== 'undefined' ? window : (typeof global !== 'undefined' ? global : (typeof self !== 'undefined' ? self : null));
  if (_g && typeof _g.__yspMakeCKey === 'function') {
    return _g.__yspMakeCKey(cnlId, unixSeconds, APP_VER, guid, PLATFORM)
  }
  throw new Error('missing local makeCKey implementation')
}

function getTokenRnd(guid, ts) {
  return getMD5('token_rnd;' + YSP_APP_ID + ';v1;' + guid + ';' + ts + ';')
}

function requestOpenApiToken(guid, rnd, ts) {
  var url = appendQuery(OPEN_API_TOKEN_URL, {
    yspappid: YSP_APP_ID,
    guid: guid,
    vappid: '59306155',
    vsecret: 'b42702bf7309a179d102f3d51b1add2fda0bc7ada64cb801',
    raw: '1',
    version: 'v1',
    ts: ts,
    rnd: rnd
  });
  var ApiTokenHeaders = [
    { header: 'Accept', value: 'application/json, text/plain, */*' },
    { header: 'Origin', value: 'https://www.yangshipin.cn' },
    { header: 'Referer', value: DEFAULT_REFERER },
    { header: 'User-Agent', value: DESKTOP_UA },
    { header: 'Cookie', value: buildYspCookie(guid) },

  ]
  var data = urlRequestWithParaAndCoding(url, ApiTokenHeaders, 'utf-8');
  return parseJsonBody(data)
  function parseJsonBody(text) {
    try {
      return JSON.parse(String(text || ''));
    } catch (err) {
    }
  }
}

function requestLiveInfoForQuality(pid, cnlId, stream, defn, guid, cKey, seq, requestId, openApiToken, playerToken, yspTicket) {
  var liveBody = buildLiveInfoBody(pid, cnlId, stream, defn, guid, cKey);
  var sdkInput = getMD5(sortedFormText(liveBody));
  var sdkSign;
  sdkSign = getOpenapiSignature(sdkInput, guid, seq, requestId, openApiToken)
  liveBody.rand_str = randomString(10);
  liveBody.signature = getLiveInfoSignature(liveBody)
  var playUrl = getLiveInfo(guid, seq, requestId, sdkInput, sdkSign, playerToken, yspTicket, liveBody)
  return playUrl
}

function getLiveInfo(guid, seq, requestId, sdkInput, sdkSign, playerToken, yspTicket, liveBody) {
  var postUrl = PLAYER_API_BASE + '/v1/player/get_live_info';
  var postHeaders = [
    { header: 'Accept', value: 'application/json, text/plain, */*' },
    { header: 'Content-Type', value: 'application/json;charset=UTF-8' },
    { header: 'Origin', value: 'https://www.yangshipin.cn' },
    { header: 'Referer', value: DEFAULT_REFERER },
    { header: 'User-Agent', value: DESKTOP_UA },
    { header: 'Cookie', value: buildYspCookie(guid, seq, requestId) },
    { header: 'yspappid', value: String(YSP_APP_ID) },
    { header: 'yspsdkinput', value: String(sdkInput) },
    { header: 'yspsdksign', value: String(sdkSign) },
    { header: 'seqId', value: String(seq) },          // 强转字符串
    { header: 'request-id', value: String(requestId) }, // 强转字符串
    { header: 'yspPlayerToken', value: String(playerToken) },
    { header: 'yspticket', value: String(yspTicket) }
  ];
  var postData = JSON.stringify(liveBody);
  var playInfo = "";
  try {
    playInfo = postWithHeaders(postUrl, postData, postHeaders, 'application/json;charset=UTF-8');
  } catch (e) {
    console.log("请求失败：" + e.message);
    return "";
  }
  if (!playInfo) return "";
  var playUrl = "";
  try {
    var json = JSON.parse(playInfo);
    if (json && json.data && json.data.playurl) {
      playUrl = json.data.playurl;
    }
  } catch (e) { }
  return playUrl;
}

function makeYspTicket(input) {
  var payload = buildPayload(input);
  return encryptPayload(payload.bytes).hex;
}

function buildPayload(input) {
  if (!input || input.livepid == null || input.authTs == null || input.guid == null) {
    throw new TypeError('buildPayload requires livepid, authTs, and guid');
  }
  var nowSeconds = input.nowSeconds == null
    ? Math.floor(Date.now() / 1000)
    : Math.floor(Number(input.nowSeconds));
  var appid = input.appid == null ? '519748109' : String(input.appid);
  var suffix10 = input.suffix10 == null ? suffix10FromNowSeconds(nowSeconds) : String(input.suffix10);
  var text = input.livepid + '&' + input.authTs + '&' + input.guid + '&' + appid + '&' + suffix10;

  var bytes = strToUint8Array(text);
  var nulTerminatedBytes = strToUint8Array(text + '\0');

  return {
    text: text,
    suffix10: suffix10,
    nowSeconds: nowSeconds,
    bytes: bytes,
    nulTerminatedBytes: nulTerminatedBytes
  };
}

function encryptPayload(payloadBytes, options) {
  if (options == null) options = {};
  var source = toUint8Array(payloadBytes);
  var key = options.key16 ? toUint8Array(options.key16) : deriveBufA16();
  var counter = options.counter16 ? toUint8Array(options.counter16) : deriveCounter16();
  if (counter.length !== 16) throw new RangeError('counter must be 16 bytes, got ' + counter.length);

  var out = new Array(source.length);
  for (var _i = 0; _i < out.length; _i++) out[_i] = 0;
  var blocks = [];
  for (var pos = 0; pos < source.length; pos += 16) {
    var counterBefore = counter.slice();
    var keystream = aes128EcbBlock(key, counter);
    var take = Math.min(16, source.length - pos);

    for (var i = 0; i < take; i++) {
      out[pos + i] = source[pos + i] ^ keystream[i];
    }

    incrementCounter128BE(counter);
    blocks.push({
      counterBeforeHex: uint8ArrayToHex(counterBefore),
      keystreamHex: uint8ArrayToHex(keystream),
      counterAfterHex: uint8ArrayToHex(counter),
      usedBytes: take
    });
  }
  return {
    bytes: out,
    hex: uint8ArrayToHex(out),
    key16Hex: uint8ArrayToHex(key),
    initialCounter16Hex: blocks[0] ? blocks[0].counterBeforeHex : uint8ArrayToHex(counter),
    finalCounter16Hex: uint8ArrayToHex(counter),
    counterBlocks: blocks
  };
}

function mul64(low1, high1, low2, high2) {
  var a0 = low1 & 0xFFFF, a1 = (low1 >>> 16) & 0xFFFF;
  var a2 = high1 & 0xFFFF, a3 = (high1 >>> 16) & 0xFFFF;
  var b0 = low2 & 0xFFFF, b1 = (low2 >>> 16) & 0xFFFF;
  var b2 = high2 & 0xFFFF, b3 = (high2 >>> 16) & 0xFFFF;
  var r0 = a0 * b0;
  var r1 = a1 * b0 + a0 * b1;
  var r2 = a2 * b0 + a1 * b1 + a0 * b2;
  var r3 = a3 * b0 + a2 * b1 + a1 * b2 + a0 * b3;
  r1 += r0 >>> 16; r0 &= 0xFFFF;
  r2 += r1 >>> 16; r1 &= 0xFFFF;
  r3 += r2 >>> 16; r2 &= 0xFFFF;
  r3 &= 0xFFFF;
  return { low: (r1 << 16) | r0, high: (r3 << 16) | r2 };
}

function suffix10FromNowSeconds(nowSeconds) {
  var seed_low = (Math.floor(Number(nowSeconds)) - 1) >>> 0;
  var seed_high = 0;
  var out = '';
  for (var i = 0; i < 10; i++) {
    var result = mul64(seed_low, seed_high, LCG_MUL_LOW, LCG_MUL_HIGH);
    seed_low = (result.low + 1) >>> 0;
    seed_high = result.high;
    if (result.low === 0xFFFFFFFF) seed_high = (seed_high + 1) >>> 0;
    out += ALPHABET_62[(seed_high >>> 1) % 62];
  }
  return out;
}

function strToUint8Array(str) { var buf = new Array(str.length); for (var i = 0; i < str.length; i++) { buf[i] = str.charCodeAt(i) & 0xff } return buf }

// 工具：统一转为 Uint8Array（替代 toBuffer）
function toUint8Array(value) { if (typeof value === 'string') { return strToUint8Array(value) } var arr = new Array(value.length); for (var i = 0; i < value.length; i++) { arr[i] = value[i] } return arr }


function getOpenapiSignature(sdkInput, guid, seq, requestId, openApiToken) {
  var input = sdkInput + '-' + guid + '-' + seq + '-' + requestId
  var tokenData = openApiToken && (openApiToken.data || openApiToken)
  var token = tokenData && tokenData.token ? String(tokenData.token) : ''
  var raw = getMD5(guid + ';www.yangshipin.cn;' + token + ';https:;' + YSP_APP_ID + ';' + input + ';')
  return raw + '-' + input
}
function getLiveInfoSignature(liveBody) {
  return getMD5(sortedFormText(liveBody) + LIVE_INFO_SIGN_SALT)
}

function buildLiveInfoBody(pid, cnlId, stream, defn, guid, cKey) {
  return {
    cnlid: cnlId,
    livepid: pid,
    stream: stream,
    guid: guid,
    cKey: cKey,
    adjust: 1,
    sphttps: '1',
    platform: PLATFORM,
    cmd: '2',
    encryptVer: '8.1',
    dtype: '1',
    devid: 'devid',
    otype: 'ojson',
    appVer: APP_VER,
    app_version: APP_VER,
    channel: 'ysp_tx',
    defn: defn
  };
}

function now() {
  return new Date().getTime();
}
function setKey(key, val, timeout) {
  if (timeout == null) timeout = (60 * 60);
  var gs = generateSign();
  var url = 'http://user.xiaoyouzb.net/source_cache/set?k=' + key + '&v=' + val + '&tos=' + timeout + '&s_key=' + gs.sign + '&s_time=' + gs.timestamp
  urlRequest(url)
}
function getKey(key) {
  var gs = generateSign();
  var url = 'http://user.xiaoyouzb.net/source_cache/get?k=' + key + '&s_key=' + gs.sign + '&s_time=' + gs.timestamp
  var data = urlRequest(url)
  return JSON.parse(data).data
}
function generateSign() { var timestamp = Math.floor(Date.now() / 1000); var sign = getMD5('source_cache_' + timestamp); return { timestamp: timestamp, sign: sign } }

function base64encode(str) { var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1); var out, i, len; var c1, c2, c3; len = str.length; i = 0; out = ""; while (i < len) { c1 = str.charCodeAt(i++) & 0xff; if (i == len) { out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt((c1 & 0x3) << 4); out += "=="; break } c2 = str.charCodeAt(i++); if (i == len) { out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4)); out += base64EncodeChars.charAt((c2 & 0xF) << 2); out += "="; break } c3 = str.charCodeAt(i++); out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4)); out += base64EncodeChars.charAt(((c2 & 0xF) << 2) | ((c3 & 0xC0) >> 6)); out += base64EncodeChars.charAt(c3 & 0x3F) } return out }

function base64decode(str) { var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1); var c1, c2, c3, c4; var i, len, out; len = str.length; i = 0; out = ""; while (i < len) { do { c1 = base64DecodeChars[str.charCodeAt(i++) & 0xff] } while (i < len && c1 == -1); if (c1 == -1) break; do { c2 = base64DecodeChars[str.charCodeAt(i++) & 0xff] } while (i < len && c2 == -1); if (c2 == -1) break; out += String.fromCharCode((c1 << 2) | ((c2 & 0x30) >> 4)); do { c3 = str.charCodeAt(i++) & 0xff; if (c3 == 61) return out; c3 = base64DecodeChars[c3] } while (i < len && c3 == -1); if (c3 == -1) break; out += String.fromCharCode(((c2 & 0XF) << 4) | ((c3 & 0x3C) >> 2)); do { c4 = str.charCodeAt(i++) & 0xff; if (c4 == 61) return out; c4 = base64DecodeChars[c4] } while (i < len && c4 == -1); if (c4 == -1) break; out += String.fromCharCode(((c3 & 0x03) << 6) | c4) } return out }

// 工具：十六进制字符串转 Uint8Array（替代 Buffer.from(hex, 'hex')）
function hexToUint8Array(hex) { var len = hex.length; var buf = new Array(len / 2); for (var i = 0; i < len; i += 2) { buf[i / 2] = parseInt(hex.substr(i, 2), 16) } return buf }

// 工具：Uint8Array 转十六进制字符串（替代 buf.toString('hex')）
function uint8ArrayToHex(arr) { var out = ''; for (var i = 0; i < arr.length; i++) { var h = arr[i].toString(16); if (h.length < 2) h = '0' + h; out += h } return out }
function func415Like(hexText) {
  var decoded = hexToUint8Array(hexText);
  var normalized = new Array(decoded.length);
  for (var i = 0; i < decoded.length; i++) {
    normalized[i] = (decoded[i] - 3) & 0xff;
  }

  var out = new Array(normalized.length + 1);
  for (var i = 0; i < normalized.length; i++) {
    out[i] = normalized[i] ^ FUNC415_MASK[i % FUNC415_MASK.length];
  }
  out[normalized.length] = 0;
  return out;
}

function deriveBufA16() {
  var buf = func415Like(CONST_1242);
  return buf.slice(10, 26);
}

function deriveCounter16() {
  var buf = func415Like(CONST_1315);
  return buf.slice(10, 26);
}

// AES-128-ECB 无填充加密（对齐 Node crypto 行为）
function aes128EcbBlock(key16, block16) {
  var key = toUint8Array(key16);
  var block = toUint8Array(block16);

  if (key.length !== 16) throw new RangeError('AES-128 key must be 16 bytes, got ' + key.length);
  if (block.length !== 16) throw new RangeError('AES block must be 16 bytes, got ' + block.length);
  var keyWA = uint8ToWordArray(key);
  var blockWA = uint8ToWordArray(block);
  var encryptedWA = CryptoJS.AES.encrypt(blockWA, keyWA, {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.NoPadding
  });

  var cipherBuf = hexToUint8Array(encryptedWA.ciphertext.toString(CryptoJS.enc.Hex));
  return cipherBuf;
}
function incrementCounter128BE(counter16) {
  for (var i = 15; i >= 0; i--) {
    counter16[i] = (counter16[i] + 1) & 0xff;
    if (counter16[i] !== 0) break;
  }
}

function uint8ToWordArray(u8) { var words = []; var len = u8.length; for (var i = 0; i < len; i += 4) { var w = 0; w |= (u8[i] << 24); w |= ((u8[i + 1] || 0) << 16); w |= ((u8[i + 2] || 0) << 8); w |= (u8[i + 3] || 0); words.push(w >>> 0) } return CryptoJS.lib.WordArray.create(words, len) }


function cctvgwParsePlay(originUrl) {
  try {
    var version = getVersion()
    var packageName = getPackageName()
    var isShow = checkPackageVersion(packageName, version)
    if (!isShow) {
      return ['http://abc.sddsff.m3u8']
    }
  } catch (error) {
    return ['http://abc.sddsff.m3u8']
  }
  var key = originUrl.split('//')[1];
  var url = 'http://' + key
  return [url]
}

// ysp1://1//600001859//2024078201
// ysp1://2//82dcaeb46bb7f68703dbc90390a387
function ysponeParsePlay(originUrl) {
  var key, id1, id2, url, token
  key = originUrl.split('//')[1];
  if (key == 1) {
    id1 = originUrl.split('//')[2];
    id2 = originUrl.split('//')[3];
    url = 'http://36.137.183.45:1100/ysp2026/ysp.php?pid=' + id1 + '&id=' + id2 + '&key=your_secret_key_here05322100'
  } else {
    token = originUrl.split('//')[2];
    url = 'http://live.bypbwxx.top/live/jmysp.php?token=' + token
  }
  return [url]
}
// miguold://608807420//1
function miguoldParsePlay(originUrl) {
  var token = originUrl.split('//')[1];
  var urlNum = originUrl.split('//')[2];
  var url = 'http://live.bypbwxx.top/live/jmmigu' + urlNum + '.php?token=' + token
  return [url]
}
function fgtvParsePlay(originUrl) {
  var token = originUrl.split('//')[1];
  var url = 'http://live.bypbwxx.top/live/jm-ws0503.php?token=' + token
  return [url]
}