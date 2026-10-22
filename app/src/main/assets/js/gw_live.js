var CryptoJS=CryptoJS||(function(Math,undefined){var crypto;if(typeof window!=='undefined'&&window.crypto){crypto=window.crypto}if(typeof self!=='undefined'&&self.crypto){crypto=self.crypto}if(typeof globalThis!=='undefined'&&globalThis.crypto){crypto=globalThis.crypto}if(!crypto&&typeof window!=='undefined'&&window.msCrypto){crypto=window.msCrypto}if(!crypto&&typeof global!=='undefined'&&global.crypto){crypto=global.crypto}if(!crypto&&typeof require==='function'){try{crypto=require('crypto')}catch(err){}}var cryptoSecureRandomInt=function(){if(crypto){if(typeof crypto.getRandomValues==='function'){try{return crypto.getRandomValues(new Uint32Array(1))[0]}catch(err){}}if(typeof crypto.randomBytes==='function'){try{return crypto.randomBytes(4).readInt32LE()}catch(err){}}}throw new Error('Native crypto module could not be used to get secure random number.');};var create=Object.create||(function(){function F(){}return function(obj){var subtype;F.prototype=obj;subtype=new F();F.prototype=null;return subtype}}());var C={};var C_lib=C.lib={};var Base=C_lib.Base=(function(){return{extend:function(overrides){var subtype=create(this);if(overrides){subtype.mixIn(overrides)}if(!subtype.hasOwnProperty('init')||this.init===subtype.init){subtype.init=function(){subtype.$super.init.apply(this,arguments)}}subtype.init.prototype=subtype;subtype.$super=this;return subtype},create:function(){var instance=this.extend();instance.init.apply(instance,arguments);return instance},init:function(){},mixIn:function(properties){for(var propertyName in properties){if(properties.hasOwnProperty(propertyName)){this[propertyName]=properties[propertyName]}}if(properties.hasOwnProperty('toString')){this.toString=properties.toString}},clone:function(){return this.init.prototype.extend(this)}}}());var WordArray=C_lib.WordArray=Base.extend({init:function(words,sigBytes){words=this.words=words||[];if(sigBytes!=undefined){this.sigBytes=sigBytes}else{this.sigBytes=words.length*4}},toString:function(encoder){return(encoder||Hex).stringify(this)},concat:function(wordArray){var thisWords=this.words;var thatWords=wordArray.words;var thisSigBytes=this.sigBytes;var thatSigBytes=wordArray.sigBytes;this.clamp();if(thisSigBytes%4){for(var i=0;i<thatSigBytes;i++){var thatByte=(thatWords[i>>>2]>>>(24-(i%4)*8))&0xff;thisWords[(thisSigBytes+i)>>>2]|=thatByte<<(24-((thisSigBytes+i)%4)*8)}}else{for(var j=0;j<thatSigBytes;j+=4){thisWords[(thisSigBytes+j)>>>2]=thatWords[j>>>2]}}this.sigBytes+=thatSigBytes;return this},clamp:function(){var words=this.words;var sigBytes=this.sigBytes;words[sigBytes>>>2]&=0xffffffff<<(32-(sigBytes%4)*8);words.length=Math.ceil(sigBytes/4)},clone:function(){var clone=Base.clone.call(this);clone.words=this.words.slice(0);return clone},random:function(nBytes){var words=[];for(var i=0;i<nBytes;i+=4){words.push(cryptoSecureRandomInt())}return new WordArray.init(words,nBytes)}});var C_enc=C.enc={};var Hex=C_enc.Hex={stringify:function(wordArray){var words=wordArray.words;var sigBytes=wordArray.sigBytes;var hexChars=[];for(var i=0;i<sigBytes;i++){var bite=(words[i>>>2]>>>(24-(i%4)*8))&0xff;hexChars.push((bite>>>4).toString(16));hexChars.push((bite&0x0f).toString(16))}return hexChars.join('')},parse:function(hexStr){var hexStrLength=hexStr.length;var words=[];for(var i=0;i<hexStrLength;i+=2){words[i>>>3]|=parseInt(hexStr.substr(i,2),16)<<(24-(i%8)*4)}return new WordArray.init(words,hexStrLength/2)}};var Latin1=C_enc.Latin1={stringify:function(wordArray){var words=wordArray.words;var sigBytes=wordArray.sigBytes;var latin1Chars=[];for(var i=0;i<sigBytes;i++){var bite=(words[i>>>2]>>>(24-(i%4)*8))&0xff;latin1Chars.push(String.fromCharCode(bite))}return latin1Chars.join('')},parse:function(latin1Str){var latin1StrLength=latin1Str.length;var words=[];for(var i=0;i<latin1StrLength;i++){words[i>>>2]|=(latin1Str.charCodeAt(i)&0xff)<<(24-(i%4)*8)}return new WordArray.init(words,latin1StrLength)}};var Utf8=C_enc.Utf8={stringify:function(wordArray){try{return decodeURIComponent(escape(Latin1.stringify(wordArray)))}catch(e){throw new Error('Malformed UTF-8 data');}},parse:function(utf8Str){return Latin1.parse(unescape(encodeURIComponent(utf8Str)))}};var BufferedBlockAlgorithm=C_lib.BufferedBlockAlgorithm=Base.extend({reset:function(){this._data=new WordArray.init();this._nDataBytes=0},_append:function(data){if(typeof data=='string'){data=Utf8.parse(data)}this._data.concat(data);this._nDataBytes+=data.sigBytes},_process:function(doFlush){var processedWords;var data=this._data;var dataWords=data.words;var dataSigBytes=data.sigBytes;var blockSize=this.blockSize;var blockSizeBytes=blockSize*4;var nBlocksReady=dataSigBytes/blockSizeBytes;if(doFlush){nBlocksReady=Math.ceil(nBlocksReady)}else{nBlocksReady=Math.max((nBlocksReady|0)-this._minBufferSize,0)}var nWordsReady=nBlocksReady*blockSize;var nBytesReady=Math.min(nWordsReady*4,dataSigBytes);if(nWordsReady){for(var offset=0;offset<nWordsReady;offset+=blockSize){this._doProcessBlock(dataWords,offset)}processedWords=dataWords.splice(0,nWordsReady);data.sigBytes-=nBytesReady}return new WordArray.init(processedWords,nBytesReady)},clone:function(){var clone=Base.clone.call(this);clone._data=this._data.clone();return clone},_minBufferSize:0});var Hasher=C_lib.Hasher=BufferedBlockAlgorithm.extend({cfg:Base.extend(),init:function(cfg){this.cfg=this.cfg.extend(cfg);this.reset()},reset:function(){BufferedBlockAlgorithm.reset.call(this);this._doReset()},update:function(messageUpdate){this._append(messageUpdate);this._process();return this},finalize:function(messageUpdate){if(messageUpdate){this._append(messageUpdate)}var hash=this._doFinalize();return hash},blockSize:512/32,_createHelper:function(hasher){return function(message,cfg){return new hasher.init(cfg).finalize(message)}},_createHmacHelper:function(hasher){return function(message,key){return new C_algo.HMAC.init(hasher,key).finalize(message)}}});var C_algo=C.algo={};return C}(Math));(function(CryptoJS){(function(){var C=CryptoJS;var C_lib=C.lib;var WordArray=C_lib.WordArray;var C_enc=C.enc;var Base64=C_enc.Base64={stringify:function(wordArray){var words=wordArray.words;var sigBytes=wordArray.sigBytes;var map=this._map;wordArray.clamp();var base64Chars=[];for(var i=0;i<sigBytes;i+=3){var byte1=(words[i>>>2]>>>(24-(i%4)*8))&0xff;var byte2=(words[(i+1)>>>2]>>>(24-((i+1)%4)*8))&0xff;var byte3=(words[(i+2)>>>2]>>>(24-((i+2)%4)*8))&0xff;var triplet=(byte1<<16)|(byte2<<8)|byte3;for(var j=0;(j<4)&&(i+j*0.75<sigBytes);j++){base64Chars.push(map.charAt((triplet>>>(6*(3-j)))&0x3f))}}var paddingChar=map.charAt(64);if(paddingChar){while(base64Chars.length%4){base64Chars.push(paddingChar)}}return base64Chars.join('')},parse:function(base64Str){var base64StrLength=base64Str.length;var map=this._map;var reverseMap=this._reverseMap;if(!reverseMap){reverseMap=this._reverseMap=[];for(var j=0;j<map.length;j++){reverseMap[map.charCodeAt(j)]=j}}var paddingChar=map.charAt(64);if(paddingChar){var paddingIndex=base64Str.indexOf(paddingChar);if(paddingIndex!==-1){base64StrLength=paddingIndex}}return parseLoop(base64Str,base64StrLength,reverseMap)},_map:'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='};function parseLoop(base64Str,base64StrLength,reverseMap){var words=[];var nBytes=0;for(var i=0;i<base64StrLength;i++){if(i%4){var bits1=reverseMap[base64Str.charCodeAt(i-1)]<<((i%4)*2);var bits2=reverseMap[base64Str.charCodeAt(i)]>>>(6-(i%4)*2);var bitsCombined=bits1|bits2;words[nBytes>>>2]|=bitsCombined<<(24-(nBytes%4)*8);nBytes++}}return WordArray.create(words,nBytes)}}());return CryptoJS.enc.Base64})(CryptoJS);(function(CryptoJS){CryptoJS.lib.Cipher||(function(undefined){var C=CryptoJS;var C_lib=C.lib;var Base=C_lib.Base;var WordArray=C_lib.WordArray;var BufferedBlockAlgorithm=C_lib.BufferedBlockAlgorithm;var C_enc=C.enc;var Utf8=C_enc.Utf8;var Base64=C_enc.Base64;var C_algo=C.algo;var EvpKDF=C_algo.EvpKDF;var Cipher=C_lib.Cipher=BufferedBlockAlgorithm.extend({cfg:Base.extend(),createEncryptor:function(key,cfg){return this.create(this._ENC_XFORM_MODE,key,cfg)},createDecryptor:function(key,cfg){return this.create(this._DEC_XFORM_MODE,key,cfg)},init:function(xformMode,key,cfg){this.cfg=this.cfg.extend(cfg);this._xformMode=xformMode;this._key=key;this.reset()},reset:function(){BufferedBlockAlgorithm.reset.call(this);this._doReset()},process:function(dataUpdate){this._append(dataUpdate);return this._process()},finalize:function(dataUpdate){if(dataUpdate){this._append(dataUpdate)}var finalProcessedData=this._doFinalize();return finalProcessedData},keySize:128/32,ivSize:128/32,_ENC_XFORM_MODE:1,_DEC_XFORM_MODE:2,_createHelper:(function(){function selectCipherStrategy(key){if(typeof key=='string'){return PasswordBasedCipher}else{return SerializableCipher}}return function(cipher){return{encrypt:function(message,key,cfg){return selectCipherStrategy(key).encrypt(cipher,message,key,cfg)},decrypt:function(ciphertext,key,cfg){return selectCipherStrategy(key).decrypt(cipher,ciphertext,key,cfg)}}}}())});var StreamCipher=C_lib.StreamCipher=Cipher.extend({_doFinalize:function(){var finalProcessedBlocks=this._process(!!'flush');return finalProcessedBlocks},blockSize:1});var C_mode=C.mode={};var BlockCipherMode=C_lib.BlockCipherMode=Base.extend({createEncryptor:function(cipher,iv){return this.Encryptor.create(cipher,iv)},createDecryptor:function(cipher,iv){return this.Decryptor.create(cipher,iv)},init:function(cipher,iv){this._cipher=cipher;this._iv=iv}});var CBC=C_mode.CBC=(function(){var CBC=BlockCipherMode.extend();CBC.Encryptor=CBC.extend({processBlock:function(words,offset){var cipher=this._cipher;var blockSize=cipher.blockSize;xorBlock.call(this,words,offset,blockSize);cipher.encryptBlock(words,offset);this._prevBlock=words.slice(offset,offset+blockSize)}});CBC.Decryptor=CBC.extend({processBlock:function(words,offset){var cipher=this._cipher;var blockSize=cipher.blockSize;var thisBlock=words.slice(offset,offset+blockSize);cipher.decryptBlock(words,offset);xorBlock.call(this,words,offset,blockSize);this._prevBlock=thisBlock}});function xorBlock(words,offset,blockSize){var block;var iv=this._iv;if(iv){block=iv;this._iv=undefined}else{block=this._prevBlock}for(var i=0;i<blockSize;i++){words[offset+i]^=block[i]}}return CBC}());var C_pad=C.pad={};var Pkcs7=C_pad.Pkcs7={pad:function(data,blockSize){var blockSizeBytes=blockSize*4;var nPaddingBytes=blockSizeBytes-data.sigBytes%blockSizeBytes;var paddingWord=(nPaddingBytes<<24)|(nPaddingBytes<<16)|(nPaddingBytes<<8)|nPaddingBytes;var paddingWords=[];for(var i=0;i<nPaddingBytes;i+=4){paddingWords.push(paddingWord)}var padding=WordArray.create(paddingWords,nPaddingBytes);data.concat(padding)},unpad:function(data){var nPaddingBytes=data.words[(data.sigBytes-1)>>>2]&0xff;data.sigBytes-=nPaddingBytes}};var BlockCipher=C_lib.BlockCipher=Cipher.extend({cfg:Cipher.cfg.extend({mode:CBC,padding:Pkcs7}),reset:function(){var modeCreator;Cipher.reset.call(this);var cfg=this.cfg;var iv=cfg.iv;var mode=cfg.mode;if(this._xformMode==this._ENC_XFORM_MODE){modeCreator=mode.createEncryptor}else{modeCreator=mode.createDecryptor;this._minBufferSize=1}if(this._mode&&this._mode.__creator==modeCreator){this._mode.init(this,iv&&iv.words)}else{this._mode=modeCreator.call(mode,this,iv&&iv.words);this._mode.__creator=modeCreator}},_doProcessBlock:function(words,offset){this._mode.processBlock(words,offset)},_doFinalize:function(){var finalProcessedBlocks;var padding=this.cfg.padding;if(this._xformMode==this._ENC_XFORM_MODE){padding.pad(this._data,this.blockSize);finalProcessedBlocks=this._process(!!'flush')}else{finalProcessedBlocks=this._process(!!'flush');padding.unpad(finalProcessedBlocks)}return finalProcessedBlocks},blockSize:128/32});var CipherParams=C_lib.CipherParams=Base.extend({init:function(cipherParams){this.mixIn(cipherParams)},toString:function(formatter){return(formatter||this.formatter).stringify(this)}});var C_format=C.format={};var OpenSSLFormatter=C_format.OpenSSL={stringify:function(cipherParams){var wordArray;var ciphertext=cipherParams.ciphertext;var salt=cipherParams.salt;if(salt){wordArray=WordArray.create([0x53616c74,0x65645f5f]).concat(salt).concat(ciphertext)}else{wordArray=ciphertext}return wordArray.toString(Base64)},parse:function(openSSLStr){var salt;var ciphertext=Base64.parse(openSSLStr);var ciphertextWords=ciphertext.words;if(ciphertextWords[0]==0x53616c74&&ciphertextWords[1]==0x65645f5f){salt=WordArray.create(ciphertextWords.slice(2,4));ciphertextWords.splice(0,4);ciphertext.sigBytes-=16}return CipherParams.create({ciphertext:ciphertext,salt:salt})}};var SerializableCipher=C_lib.SerializableCipher=Base.extend({cfg:Base.extend({format:OpenSSLFormatter}),encrypt:function(cipher,message,key,cfg){cfg=this.cfg.extend(cfg);var encryptor=cipher.createEncryptor(key,cfg);var ciphertext=encryptor.finalize(message);var cipherCfg=encryptor.cfg;return CipherParams.create({ciphertext:ciphertext,key:key,iv:cipherCfg.iv,algorithm:cipher,mode:cipherCfg.mode,padding:cipherCfg.padding,blockSize:cipher.blockSize,formatter:cfg.format})},decrypt:function(cipher,ciphertext,key,cfg){cfg=this.cfg.extend(cfg);ciphertext=this._parse(ciphertext,cfg.format);var plaintext=cipher.createDecryptor(key,cfg).finalize(ciphertext.ciphertext);return plaintext},_parse:function(ciphertext,format){if(typeof ciphertext=='string'){return format.parse(ciphertext,this)}else{return ciphertext}}});var C_kdf=C.kdf={};var OpenSSLKdf=C_kdf.OpenSSL={execute:function(password,keySize,ivSize,salt,hasher){if(!salt){salt=WordArray.random(64/8)}if(!hasher){var key=EvpKDF.create({keySize:keySize+ivSize}).compute(password,salt)}else{var key=EvpKDF.create({keySize:keySize+ivSize,hasher:hasher}).compute(password,salt)}var iv=WordArray.create(key.words.slice(keySize),ivSize*4);key.sigBytes=keySize*4;return CipherParams.create({key:key,iv:iv,salt:salt})}};var PasswordBasedCipher=C_lib.PasswordBasedCipher=SerializableCipher.extend({cfg:SerializableCipher.cfg.extend({kdf:OpenSSLKdf}),encrypt:function(cipher,message,password,cfg){cfg=this.cfg.extend(cfg);var derivedParams=cfg.kdf.execute(password,cipher.keySize,cipher.ivSize,cfg.salt,cfg.hasher);cfg.iv=derivedParams.iv;var ciphertext=SerializableCipher.encrypt.call(this,cipher,message,derivedParams.key,cfg);ciphertext.mixIn(derivedParams);return ciphertext},decrypt:function(cipher,ciphertext,password,cfg){cfg=this.cfg.extend(cfg);ciphertext=this._parse(ciphertext,cfg.format);var derivedParams=cfg.kdf.execute(password,cipher.keySize,cipher.ivSize,ciphertext.salt,cfg.hasher);cfg.iv=derivedParams.iv;var plaintext=SerializableCipher.decrypt.call(this,cipher,ciphertext,derivedParams.key,cfg);return plaintext}})}())})(CryptoJS);(function(CryptoJS){(function(Math){var C=CryptoJS;var C_lib=C.lib;var WordArray=C_lib.WordArray;var Hasher=C_lib.Hasher;var C_algo=C.algo;var T=[];(function(){for(var i=0;i<64;i++){T[i]=(Math.abs(Math.sin(i+1))*0x100000000)|0}}());var MD5=C_algo.MD5=Hasher.extend({_doReset:function(){this._hash=new WordArray.init([0x67452301,0xefcdab89,0x98badcfe,0x10325476])},_doProcessBlock:function(M,offset){for(var i=0;i<16;i++){var offset_i=offset+i;var M_offset_i=M[offset_i];M[offset_i]=((((M_offset_i<<8)|(M_offset_i>>>24))&0x00ff00ff)|(((M_offset_i<<24)|(M_offset_i>>>8))&0xff00ff00))}var H=this._hash.words;var M_offset_0=M[offset+0];var M_offset_1=M[offset+1];var M_offset_2=M[offset+2];var M_offset_3=M[offset+3];var M_offset_4=M[offset+4];var M_offset_5=M[offset+5];var M_offset_6=M[offset+6];var M_offset_7=M[offset+7];var M_offset_8=M[offset+8];var M_offset_9=M[offset+9];var M_offset_10=M[offset+10];var M_offset_11=M[offset+11];var M_offset_12=M[offset+12];var M_offset_13=M[offset+13];var M_offset_14=M[offset+14];var M_offset_15=M[offset+15];var a=H[0];var b=H[1];var c=H[2];var d=H[3];a=FF(a,b,c,d,M_offset_0,7,T[0]);d=FF(d,a,b,c,M_offset_1,12,T[1]);c=FF(c,d,a,b,M_offset_2,17,T[2]);b=FF(b,c,d,a,M_offset_3,22,T[3]);a=FF(a,b,c,d,M_offset_4,7,T[4]);d=FF(d,a,b,c,M_offset_5,12,T[5]);c=FF(c,d,a,b,M_offset_6,17,T[6]);b=FF(b,c,d,a,M_offset_7,22,T[7]);a=FF(a,b,c,d,M_offset_8,7,T[8]);d=FF(d,a,b,c,M_offset_9,12,T[9]);c=FF(c,d,a,b,M_offset_10,17,T[10]);b=FF(b,c,d,a,M_offset_11,22,T[11]);a=FF(a,b,c,d,M_offset_12,7,T[12]);d=FF(d,a,b,c,M_offset_13,12,T[13]);c=FF(c,d,a,b,M_offset_14,17,T[14]);b=FF(b,c,d,a,M_offset_15,22,T[15]);a=GG(a,b,c,d,M_offset_1,5,T[16]);d=GG(d,a,b,c,M_offset_6,9,T[17]);c=GG(c,d,a,b,M_offset_11,14,T[18]);b=GG(b,c,d,a,M_offset_0,20,T[19]);a=GG(a,b,c,d,M_offset_5,5,T[20]);d=GG(d,a,b,c,M_offset_10,9,T[21]);c=GG(c,d,a,b,M_offset_15,14,T[22]);b=GG(b,c,d,a,M_offset_4,20,T[23]);a=GG(a,b,c,d,M_offset_9,5,T[24]);d=GG(d,a,b,c,M_offset_14,9,T[25]);c=GG(c,d,a,b,M_offset_3,14,T[26]);b=GG(b,c,d,a,M_offset_8,20,T[27]);a=GG(a,b,c,d,M_offset_13,5,T[28]);d=GG(d,a,b,c,M_offset_2,9,T[29]);c=GG(c,d,a,b,M_offset_7,14,T[30]);b=GG(b,c,d,a,M_offset_12,20,T[31]);a=HH(a,b,c,d,M_offset_5,4,T[32]);d=HH(d,a,b,c,M_offset_8,11,T[33]);c=HH(c,d,a,b,M_offset_11,16,T[34]);b=HH(b,c,d,a,M_offset_14,23,T[35]);a=HH(a,b,c,d,M_offset_1,4,T[36]);d=HH(d,a,b,c,M_offset_4,11,T[37]);c=HH(c,d,a,b,M_offset_7,16,T[38]);b=HH(b,c,d,a,M_offset_10,23,T[39]);a=HH(a,b,c,d,M_offset_13,4,T[40]);d=HH(d,a,b,c,M_offset_0,11,T[41]);c=HH(c,d,a,b,M_offset_3,16,T[42]);b=HH(b,c,d,a,M_offset_6,23,T[43]);a=HH(a,b,c,d,M_offset_9,4,T[44]);d=HH(d,a,b,c,M_offset_12,11,T[45]);c=HH(c,d,a,b,M_offset_15,16,T[46]);b=HH(b,c,d,a,M_offset_2,23,T[47]);a=II(a,b,c,d,M_offset_0,6,T[48]);d=II(d,a,b,c,M_offset_7,10,T[49]);c=II(c,d,a,b,M_offset_14,15,T[50]);b=II(b,c,d,a,M_offset_5,21,T[51]);a=II(a,b,c,d,M_offset_12,6,T[52]);d=II(d,a,b,c,M_offset_3,10,T[53]);c=II(c,d,a,b,M_offset_10,15,T[54]);b=II(b,c,d,a,M_offset_1,21,T[55]);a=II(a,b,c,d,M_offset_8,6,T[56]);d=II(d,a,b,c,M_offset_15,10,T[57]);c=II(c,d,a,b,M_offset_6,15,T[58]);b=II(b,c,d,a,M_offset_13,21,T[59]);a=II(a,b,c,d,M_offset_4,6,T[60]);d=II(d,a,b,c,M_offset_11,10,T[61]);c=II(c,d,a,b,M_offset_2,15,T[62]);b=II(b,c,d,a,M_offset_9,21,T[63]);H[0]=(H[0]+a)|0;H[1]=(H[1]+b)|0;H[2]=(H[2]+c)|0;H[3]=(H[3]+d)|0},_doFinalize:function(){var data=this._data;var dataWords=data.words;var nBitsTotal=this._nDataBytes*8;var nBitsLeft=data.sigBytes*8;dataWords[nBitsLeft>>>5]|=0x80<<(24-nBitsLeft%32);var nBitsTotalH=Math.floor(nBitsTotal/0x100000000);var nBitsTotalL=nBitsTotal;dataWords[(((nBitsLeft+64)>>>9)<<4)+15]=((((nBitsTotalH<<8)|(nBitsTotalH>>>24))&0x00ff00ff)|(((nBitsTotalH<<24)|(nBitsTotalH>>>8))&0xff00ff00));dataWords[(((nBitsLeft+64)>>>9)<<4)+14]=((((nBitsTotalL<<8)|(nBitsTotalL>>>24))&0x00ff00ff)|(((nBitsTotalL<<24)|(nBitsTotalL>>>8))&0xff00ff00));data.sigBytes=(dataWords.length+1)*4;this._process();var hash=this._hash;var H=hash.words;for(var i=0;i<4;i++){var H_i=H[i];H[i]=(((H_i<<8)|(H_i>>>24))&0x00ff00ff)|(((H_i<<24)|(H_i>>>8))&0xff00ff00)}return hash},clone:function(){var clone=Hasher.clone.call(this);clone._hash=this._hash.clone();return clone}});function FF(a,b,c,d,x,s,t){var n=a+((b&c)|(~b&d))+x+t;return((n<<s)|(n>>>(32-s)))+b}function GG(a,b,c,d,x,s,t){var n=a+((b&d)|(c&~d))+x+t;return((n<<s)|(n>>>(32-s)))+b}function HH(a,b,c,d,x,s,t){var n=a+(b^c^d)+x+t;return((n<<s)|(n>>>(32-s)))+b}function II(a,b,c,d,x,s,t){var n=a+(c^(b|~d))+x+t;return((n<<s)|(n>>>(32-s)))+b}C.MD5=Hasher._createHelper(MD5);C.HmacMD5=Hasher._createHmacHelper(MD5)}(Math));return CryptoJS.MD5})(CryptoJS);(function(CryptoJS){(function(Math){var C=CryptoJS;var C_lib=C.lib;var WordArray=C_lib.WordArray;var Hasher=C_lib.Hasher;var C_algo=C.algo;var H=[];var K=[];(function(){function isPrime(n){var sqrtN=Math.sqrt(n);for(var factor=2;factor<=sqrtN;factor++){if(!(n%factor)){return false}}return true}function getFractionalBits(n){return((n-(n|0))*0x100000000)|0}var n=2;var nPrime=0;while(nPrime<64){if(isPrime(n)){if(nPrime<8){H[nPrime]=getFractionalBits(Math.pow(n,1/2))}K[nPrime]=getFractionalBits(Math.pow(n,1/3));nPrime++}n++}}());var W=[];var SHA256=C_algo.SHA256=Hasher.extend({_doReset:function(){this._hash=new WordArray.init(H.slice(0))},_doProcessBlock:function(M,offset){var H=this._hash.words;var a=H[0];var b=H[1];var c=H[2];var d=H[3];var e=H[4];var f=H[5];var g=H[6];var h=H[7];for(var i=0;i<64;i++){if(i<16){W[i]=M[offset+i]|0}else{var gamma0x=W[i-15];var gamma0=((gamma0x<<25)|(gamma0x>>>7))^((gamma0x<<14)|(gamma0x>>>18))^(gamma0x>>>3);var gamma1x=W[i-2];var gamma1=((gamma1x<<15)|(gamma1x>>>17))^((gamma1x<<13)|(gamma1x>>>19))^(gamma1x>>>10);W[i]=gamma0+W[i-7]+gamma1+W[i-16]}var ch=(e&f)^(~e&g);var maj=(a&b)^(a&c)^(b&c);var sigma0=((a<<30)|(a>>>2))^((a<<19)|(a>>>13))^((a<<10)|(a>>>22));var sigma1=((e<<26)|(e>>>6))^((e<<21)|(e>>>11))^((e<<7)|(e>>>25));var t1=h+sigma1+ch+K[i]+W[i];var t2=sigma0+maj;h=g;g=f;f=e;e=(d+t1)|0;d=c;c=b;b=a;a=(t1+t2)|0}H[0]=(H[0]+a)|0;H[1]=(H[1]+b)|0;H[2]=(H[2]+c)|0;H[3]=(H[3]+d)|0;H[4]=(H[4]+e)|0;H[5]=(H[5]+f)|0;H[6]=(H[6]+g)|0;H[7]=(H[7]+h)|0},_doFinalize:function(){var data=this._data;var dataWords=data.words;var nBitsTotal=this._nDataBytes*8;var nBitsLeft=data.sigBytes*8;dataWords[nBitsLeft>>>5]|=0x80<<(24-nBitsLeft%32);dataWords[(((nBitsLeft+64)>>>9)<<4)+14]=Math.floor(nBitsTotal/0x100000000);dataWords[(((nBitsLeft+64)>>>9)<<4)+15]=nBitsTotal;data.sigBytes=dataWords.length*4;this._process();return this._hash},clone:function(){var clone=Hasher.clone.call(this);clone._hash=this._hash.clone();return clone}});C.SHA256=Hasher._createHelper(SHA256);C.HmacSHA256=Hasher._createHmacHelper(SHA256)}(Math));return CryptoJS.SHA256})(CryptoJS);(function(CryptoJS){(function(){var C=CryptoJS;var C_lib=C.lib;var BlockCipher=C_lib.BlockCipher;var C_algo=C.algo;var SBOX=[];var INV_SBOX=[];var SUB_MIX_0=[];var SUB_MIX_1=[];var SUB_MIX_2=[];var SUB_MIX_3=[];var INV_SUB_MIX_0=[];var INV_SUB_MIX_1=[];var INV_SUB_MIX_2=[];var INV_SUB_MIX_3=[];(function(){var d=[];for(var i=0;i<256;i++){if(i<128){d[i]=i<<1}else{d[i]=(i<<1)^0x11b}}var x=0;var xi=0;for(var i=0;i<256;i++){var sx=xi^(xi<<1)^(xi<<2)^(xi<<3)^(xi<<4);sx=(sx>>>8)^(sx&0xff)^0x63;SBOX[x]=sx;INV_SBOX[sx]=x;var x2=d[x];var x4=d[x2];var x8=d[x4];var t=(d[sx]*0x101)^(sx*0x1010100);SUB_MIX_0[x]=(t<<24)|(t>>>8);SUB_MIX_1[x]=(t<<16)|(t>>>16);SUB_MIX_2[x]=(t<<8)|(t>>>24);SUB_MIX_3[x]=t;var t=(x8*0x1010101)^(x4*0x10001)^(x2*0x101)^(x*0x1010100);INV_SUB_MIX_0[sx]=(t<<24)|(t>>>8);INV_SUB_MIX_1[sx]=(t<<16)|(t>>>16);INV_SUB_MIX_2[sx]=(t<<8)|(t>>>24);INV_SUB_MIX_3[sx]=t;if(!x){x=xi=1}else{x=x2^d[d[d[x8^x2]]];xi^=d[d[xi]]}}}());var RCON=[0x00,0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36];var AES=C_algo.AES=BlockCipher.extend({_doReset:function(){var t;if(this._nRounds&&this._keyPriorReset===this._key){return}var key=this._keyPriorReset=this._key;var keyWords=key.words;var keySize=key.sigBytes/4;var nRounds=this._nRounds=keySize+6;var ksRows=(nRounds+1)*4;var keySchedule=this._keySchedule=[];for(var ksRow=0;ksRow<ksRows;ksRow++){if(ksRow<keySize){keySchedule[ksRow]=keyWords[ksRow]}else{t=keySchedule[ksRow-1];if(!(ksRow%keySize)){t=(t<<8)|(t>>>24);t=(SBOX[t>>>24]<<24)|(SBOX[(t>>>16)&0xff]<<16)|(SBOX[(t>>>8)&0xff]<<8)|SBOX[t&0xff];t^=RCON[(ksRow/keySize)|0]<<24}else if(keySize>6&&ksRow%keySize==4){t=(SBOX[t>>>24]<<24)|(SBOX[(t>>>16)&0xff]<<16)|(SBOX[(t>>>8)&0xff]<<8)|SBOX[t&0xff]}keySchedule[ksRow]=keySchedule[ksRow-keySize]^t}}var invKeySchedule=this._invKeySchedule=[];for(var invKsRow=0;invKsRow<ksRows;invKsRow++){var ksRow=ksRows-invKsRow;if(invKsRow%4){var t=keySchedule[ksRow]}else{var t=keySchedule[ksRow-4]}if(invKsRow<4||ksRow<=4){invKeySchedule[invKsRow]=t}else{invKeySchedule[invKsRow]=INV_SUB_MIX_0[SBOX[t>>>24]]^INV_SUB_MIX_1[SBOX[(t>>>16)&0xff]]^INV_SUB_MIX_2[SBOX[(t>>>8)&0xff]]^INV_SUB_MIX_3[SBOX[t&0xff]]}}},encryptBlock:function(M,offset){this._doCryptBlock(M,offset,this._keySchedule,SUB_MIX_0,SUB_MIX_1,SUB_MIX_2,SUB_MIX_3,SBOX)},decryptBlock:function(M,offset){var t=M[offset+1];M[offset+1]=M[offset+3];M[offset+3]=t;this._doCryptBlock(M,offset,this._invKeySchedule,INV_SUB_MIX_0,INV_SUB_MIX_1,INV_SUB_MIX_2,INV_SUB_MIX_3,INV_SBOX);var t=M[offset+1];M[offset+1]=M[offset+3];M[offset+3]=t},_doCryptBlock:function(M,offset,keySchedule,SUB_MIX_0,SUB_MIX_1,SUB_MIX_2,SUB_MIX_3,SBOX){var nRounds=this._nRounds;var s0=M[offset]^keySchedule[0];var s1=M[offset+1]^keySchedule[1];var s2=M[offset+2]^keySchedule[2];var s3=M[offset+3]^keySchedule[3];var ksRow=4;for(var round=1;round<nRounds;round++){var t0=SUB_MIX_0[s0>>>24]^SUB_MIX_1[(s1>>>16)&0xff]^SUB_MIX_2[(s2>>>8)&0xff]^SUB_MIX_3[s3&0xff]^keySchedule[ksRow++];var t1=SUB_MIX_0[s1>>>24]^SUB_MIX_1[(s2>>>16)&0xff]^SUB_MIX_2[(s3>>>8)&0xff]^SUB_MIX_3[s0&0xff]^keySchedule[ksRow++];var t2=SUB_MIX_0[s2>>>24]^SUB_MIX_1[(s3>>>16)&0xff]^SUB_MIX_2[(s0>>>8)&0xff]^SUB_MIX_3[s1&0xff]^keySchedule[ksRow++];var t3=SUB_MIX_0[s3>>>24]^SUB_MIX_1[(s0>>>16)&0xff]^SUB_MIX_2[(s1>>>8)&0xff]^SUB_MIX_3[s2&0xff]^keySchedule[ksRow++];s0=t0;s1=t1;s2=t2;s3=t3}var t0=((SBOX[s0>>>24]<<24)|(SBOX[(s1>>>16)&0xff]<<16)|(SBOX[(s2>>>8)&0xff]<<8)|SBOX[s3&0xff])^keySchedule[ksRow++];var t1=((SBOX[s1>>>24]<<24)|(SBOX[(s2>>>16)&0xff]<<16)|(SBOX[(s3>>>8)&0xff]<<8)|SBOX[s0&0xff])^keySchedule[ksRow++];var t2=((SBOX[s2>>>24]<<24)|(SBOX[(s3>>>16)&0xff]<<16)|(SBOX[(s0>>>8)&0xff]<<8)|SBOX[s1&0xff])^keySchedule[ksRow++];var t3=((SBOX[s3>>>24]<<24)|(SBOX[(s0>>>16)&0xff]<<16)|(SBOX[(s1>>>8)&0xff]<<8)|SBOX[s2&0xff])^keySchedule[ksRow++];M[offset]=t0;M[offset+1]=t1;M[offset+2]=t2;M[offset+3]=t3},keySize:256/32});C.AES=BlockCipher._createHelper(AES)}());return CryptoJS.AES})(CryptoJS);(function(CryptoJS){(function(){var C=CryptoJS;var C_lib=C.lib;var Base=C_lib.Base;var C_enc=C.enc;var Utf8=C_enc.Utf8;var C_algo=C.algo;var HMAC=C_algo.HMAC=Base.extend({init:function(hasher,key){hasher=this._hasher=new hasher.init();if(typeof key=='string'){key=Utf8.parse(key)}var hasherBlockSize=hasher.blockSize;var hasherBlockSizeBytes=hasherBlockSize*4;if(key.sigBytes>hasherBlockSizeBytes){key=hasher.finalize(key)}key.clamp();var oKey=this._oKey=key.clone();var iKey=this._iKey=key.clone();var oKeyWords=oKey.words;var iKeyWords=iKey.words;for(var i=0;i<hasherBlockSize;i++){oKeyWords[i]^=0x5c5c5c5c;iKeyWords[i]^=0x36363636}oKey.sigBytes=iKey.sigBytes=hasherBlockSizeBytes;this.reset()},reset:function(){var hasher=this._hasher;hasher.reset();hasher.update(this._iKey)},update:function(messageUpdate){this._hasher.update(messageUpdate);return this},finalize:function(messageUpdate){var hasher=this._hasher;var innerHash=hasher.finalize(messageUpdate);hasher.reset();var hmac=hasher.finalize(this._oKey.clone().concat(innerHash));return hmac}})}())})(CryptoJS);function base64encode(str){var base64EncodeChars="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";var base64DecodeChars=new Array(-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,-1,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,-1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1);var out,i,len;var c1,c2,c3;len=str.length;i=0;out="";while(i<len){c1=str.charCodeAt(i++)&0xff;if(i==len){out+=base64EncodeChars.charAt(c1>>2);out+=base64EncodeChars.charAt((c1&0x3)<<4);out+="==";break}c2=str.charCodeAt(i++);if(i==len){out+=base64EncodeChars.charAt(c1>>2);out+=base64EncodeChars.charAt(((c1&0x3)<<4)|((c2&0xf0)>>4));out+=base64EncodeChars.charAt((c2&0xf)<<2);out+="=";break}c3=str.charCodeAt(i++);out+=base64EncodeChars.charAt(c1>>2);out+=base64EncodeChars.charAt(((c1&0x3)<<4)|((c2&0xf0)>>4));out+=base64EncodeChars.charAt(((c2&0xf)<<2)|((c3&0xc0)>>6));out+=base64EncodeChars.charAt(c3&0x3f)}return out}

function base64encode(str) { var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1); var out, i, len; var c1, c2, c3; len = str.length; i = 0; out = ""; while (i < len) { c1 = str.charCodeAt(i++) & 0xff; if (i == len) { out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt((c1 & 0x3) << 4); out += "=="; break } c2 = str.charCodeAt(i++); if (i == len) { out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4)); out += base64EncodeChars.charAt((c2 & 0xF) << 2); out += "="; break } c3 = str.charCodeAt(i++); out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4)); out += base64EncodeChars.charAt(((c2 & 0xF) << 2) | ((c3 & 0xC0) >> 6)); out += base64EncodeChars.charAt(c3 & 0x3F) } return out }

function base64decode(str) { var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1); var c1, c2, c3, c4; var i, len, out; len = str.length; i = 0; out = ""; while (i < len) { do { c1 = base64DecodeChars[str.charCodeAt(i++) & 0xff] } while (i < len && c1 == -1); if (c1 == -1) break; do { c2 = base64DecodeChars[str.charCodeAt(i++) & 0xff] } while (i < len && c2 == -1); if (c2 == -1) break; out += String.fromCharCode((c1 << 2) | ((c2 & 0x30) >> 4)); do { c3 = str.charCodeAt(i++) & 0xff; if (c3 == 61) return out; c3 = base64DecodeChars[c3] } while (i < len && c3 == -1); if (c3 == -1) break; out += String.fromCharCode(((c2 & 0XF) << 4) | ((c3 & 0x3C) >> 2)); do { c4 = str.charCodeAt(i++) & 0xff; if (c4 == 61) return out; c4 = base64DecodeChars[c4] } while (i < len && c4 == -1); if (c4 == -1) break; out += String.fromCharCode(((c3 & 0x03) << 6) | c4) } return out }

// 福建官网解析  fjgw://2
function fjgwParser(originUrl) {
  var id = originUrl.split('//')[1];
  var url = 'http://live.fjtv.net/m2o/channel/channel_info.php?channel_id=' + id;
  var header = [
    { header: 'Referer', value: 'http://live.fjtv.net/setv/' },
    { header: 'User-Agent', value: 'Mozilla/5.0' }
  ];
  var data = urlRequestWithParaAndCoding(url, header, 'utf-8');
  var url1 = JSON.parse(data)[0].m3u8;
  return [url1]
}

// 山西官网 sxgw://sxws//1000
function sxgwParser(originUrl) {
  var index = originUrl.split('//')[1];
  var HZ = originUrl.split('//')[2];
  var table = {
    'sxws': 'q8RVWgs',
    'hhdst': 'lce1mC4',
    'sxwtsh': 'Y00Xezi',
    'sxys': 'Md571Kv',
    'sxshyfz': 'p4y5do9',
    'sxjjkj': '4j01KWX',
  };
  var id = table[index];
  var i = new Date();
  var c1 = new Date(i.getFullYear(), i.getMonth(), i.getDate()).getTime();
  var ref = getC(id, c1);
  return ['https://livehhhttps.sxrtv.com/lsdream/' + id + '/' + HZ + '/' + ref + '.m3u8'];
  function getC(id, time) { var b = -1, g = 0, d = 0, o = id; for (r = 0; r < o.length; r++) { var _ = o.charCodeAt(r); d += _; -1 != b && (g += b - _); b = _ } var l = (d += g).toString(36); var a = time; var p = a.toString(36), s = 0; for (r = 0; r < p.length; r++) { s += p.charCodeAt(r) } p = p.substr(5) + p.substr(0, 5); var w, m, c = Math.abs(s - d), u = (p = x(l) + p).substr(0, 4), h = p.substr(4), v = new Array(), f = y(a) % 2; for (r = 0; r < o.length; r++) { r % 2 == f ? v.push(p.charAt(r % p.length)) : (w = o.charAt(r - 1)) ? (-1 == (m = u.indexOf(w)) ? v.push(w) : v.push(h.charAt(m))) : v.push(u.charAt(r)) } return (x(c.toString(36)) + v.join("")).substr(0, o.length); function x(t) { return t.split("").reverse().join("") } function y(t) { return new Date(t).getDay() } }
}

// https://php.17186.eu.org/gdtv/data.json
function LzwParse(originUrl) {
  var id = originUrl.replace("lzw://", "");
  var cdnConfig = urlRequest('https://php.17186.eu.org/gdtv/data.json');
  var data = JSON.parse(cdnConfig)
  for (var k in data) {
    if (data[k].pkid == id) {
      return [data[k].url];
      break;
    }
  }
  return [];
}

// https://app.hfbtc.cn/shows/2/1.html获取
// hefei://hfxw
function hefeiParser(originUrl) {
  var id = originUrl.replace("hefei://", "");
  var data = {
    'hfxw': 'xw',//合肥新闻
    'hfsh': 'sh',//合肥生活
    'hfcj': 'cj',//合肥财经
    'hfjy': 'fz',//合肥教育法制
    'hfgs': 'gs',//合肥故事休闲
  }
  var htmlInfo = urlRequest('https://app.hfbtc.cn/shows/2/1.html');
  var match = htmlInfo.match(/<source\s+src="([^"]+)"/); // 获取source标签中的src
  var url = ''
  // https://app.hfbtc.cn/hls/xwpd.m3u8?key=9521e93848232f1d4c9614955894dc09&st=ZK
  if (match) {
    var key = match[1].split('?')[1];
    url = 'https://app.hfbtc.cn/hls/' + data[id] + 'pd.m3u8?' + key
  }
  return [url]

}

// 山东地方台
function sddftParser(originUrl) {
  var id = originUrl.replace("sddft://", "");
  var tvIdList = {
    //济南
    jncqxw: [171, 2], //长清新闻
    //"jncqsh": [171, 6], //长清生活 //API返回中未找到该channelId
    jnjrtv: [303, 1], //济铁电视台
    jnjyzh: [85, 1], //济阳综合
    jnjyys: [85, 2], //济阳影视
    //"jnlcxw": [261, 1], //历城新闻综合 //ERR_BAD_REQUEST
    jnpyzh: [257, 1], //平阴综合
    jnpyxc: [257, 3], //平阴乡村振兴
    jnshzh: [97, 1], //商河综合
    jnshys: [97, 2], //商河影视
    jnzqzh: [195, 1], //章丘综合
    jnzqgg: [195, 2], //章丘公共
    //东营
    dyxwzh: [537, 1], //东营新闻综合
    dygg: [537, 3], //东营公共
    //"dygg2": [29, 90], //东营公共 //ENOTFOUND
    dykj: [537, 7], //东营科教
    dydyqxw: [163, 5], //东营区新闻综合
    dydyqkj: [163, 7], //东营区科教影视
    dygrzh: [237, 1], //广饶综合
    dygrkj: [237, 5], //广饶科教文艺
    //"dyklxw": [269, 3], //垦利新闻综合 //ERR_BAD_REQUEST
    dyljzh: [153, 1], //利津综合
    dyljwh: [153, 3], //利津文化生活
    //青岛
    qdhdzh: [227, 1], //黄岛综合
    qdhdsh: [227, 3], //黄岛生活
    qdjmzh: [221, 2], //即墨综合
    qdjmsh: [221, 3], //即墨生活服务
    qdjzzh: [305, 1], //胶州综合
    qdjzsh: [305, 3], //胶州生活
    //"qdlc": [173, 1], //李沧TV //ERR_BAD_RESPONSE
    qdls: [295, 1], //崂山TV
    qdlxzh: [253, 1], //莱西综合
    //"qdlxsh": [253, 3], //莱西生活 //ERR_BAD_RESPONSE
    qdpdxw: [45, 4], //平度新闻综合
    qdpdsh: [45, 5], //平度生活服务
    //潍坊
    wfxwzh: [635, 1], //潍坊新闻综合
    wfsh: [635, 5], //潍坊生活
    wfyswy: [635, 7], //潍坊影视综艺
    wfkjwl: [635, 9], //潍坊科教文旅
    wfgxq: [421, 14], //潍坊高新区
    wfaqzh: [137, 3], //安丘综合
    wfaqms: [137, 4], //安丘民生
    wfbhxw: [199, 1], //滨海新闻综合
    //"wfclzh": [1, 3], //昌乐综合 //ERR_BAD_RESPONSE
    wfcyzh: [47, 1], //昌邑综合
    //"wfcyjj": [47, 2], //昌邑经济生活 //ERR_BAD_RESPONSE
    wffzxw: [285, 1], //坊子新闻综合
    //"wfgmzh": [71, 24], //高密综合 //ERR_BAD_RESPONSE
    wfgmdj: [71, 38], //高密党建农科
    wfhtxw: [133, 1], //寒亭新闻
    wfkwtv: [127, 17], //奎文电视台
    //"wflqxw": [205, 39], //临朐新闻综合 //ERR_BAD_RESPONSE
    //"wfqzzh": [125, 2], //青州综合 //ERR_BAD_RESPONSE
    wfqzwh: [125, 3], //青州文化旅游
    wfwc: [15, 3], //潍城TV
    //"wfzcxw": [115, 23], //诸城新闻综合 //ERR_BAD_REQUEST
    wfzcsh: [115, 25], //诸城生活娱乐
    //烟台
    //"ytcd": [175, 1], //长岛TV //ERR_BAD_RESPONSE
    ytfszh: [189, 4], //福山综合
    //"ytfssh": [189, 5], //福山生活 //ERR_BAD_REQUEST
    ythyzh: [255, 1], //海阳综合
    ytlkzh: [57, 1], //龙口综合
    ytlksh: [57, 2], //龙口生活
    //"ytlszh": [245, 4], //莱山综合 //ECONNABORTED
    //"ytlsys": [245, 6], //莱山影视 //ECONNABORTED
    ytlyzh: [241, 4], //莱阳综合
    ytlyms: [241, 7], //莱阳民生综艺
    ytlzzh: [239, 1], //莱州综合
    ytmpzh: [281, 1], //牟平综合
    ytplzh: [109, 1], //蓬莱综合
    ytplzy: [109, 2], //蓬莱综艺
    ytqxzh: [165, 12], //栖霞综合
    //"ytqxpg": [165, 14], //栖霞苹果 //ERR_BAD_RESPONSE
    ytzyzh: [55, 2], //招远综合
    ytzyzy: [55, 4], //招远综艺
    //淄博
    zbbsxw: [17, 8], //博山新闻
    zbbstw: [17, 9], //博山图文
    zbgqzh: [61, 1], //高青综合
    zbgqys: [61, 2], //高青影视
    zbht1: [23, 15], //桓台综合
    //"zbht2": [23, 16], //桓台影视 //ERR_BAD_RESPONSE
    zblzxw: [151, 6], //临淄新闻综合
    zblzsh: [151, 7], //临淄生活服务
    zbyyzh: [203, 6], //沂源综合
    //"zbyysh": [203, 7], //沂源生活 //ERR_BAD_RESPONSE
    zbzcxw: [75, 1], //淄川新闻
    zbzcsh: [75, 2], //淄川生活
    zbzd1: [101, 1], //张店综合
    //"zbzd2": [101, 6], //张店2 //ERR_BAD_RESPONSE
    zbzctv1: [259, 1], //周村新闻
    zbzctv2: [259, 3], //周村生活
    //枣庄
    //"zzstzh": [243, 1], //山亭综合 //ERR_BAD_RESPONSE
    //"zzszzh": [233, 1], //枣庄市中综合 //ECONNABORTED
    zztezxw: [185, 2], //台儿庄新闻综合
    zztzzh: [103, 2], //滕州综合
    //"zztzms": [103, 3], //滕州民生 //API返回中未找到该channelId
    zzxcxw: [37, 8], //薛城新闻综合
    zzyczh: [209, 1], //峄城综合
    //滨州
    bzbctv: [249, 35], //滨城TV
    bzbxzh: [207, 3], //博兴综合
    bzbxsh: [207, 4], //博兴生活
    bzhmzh: [211, 2], //惠民综合
    bzhmys: [211, 3], //惠民影视
    bzwdzh: [169, 1], //无棣综合
    bzwdzy: [169, 21], //无棣综艺
    bzyxxw: [217, 1], //阳信新闻综合
    //"bzzhzh": [277, 1], //沾化综合 //ERR_BAD_REQUEST
    //"bzzhzy": [277, 9], //沾化综艺 //ERR_BAD_REQUEST
    //"bzzpzh": [11, 15], //邹平综合 //ERR_BAD_RESPONSE
    bzzpms: [11, 16], //邹平民生
    //德州
    dzxwzh: [179, 1], //德州新闻综合
    dzjjsh: [179, 2], //德州经济生活
    dztw: [179, 9], //德州图文
    dzlczh: [215, 6], //陵城综合
    dzllxw: [267, 1], //乐陵新闻综合
    dzllcs: [267, 5], //乐陵城市生活
    //"dzly1": [49, 3], //临邑1 //ERR_BAD_REQUEST
    //"dzly2": [49, 4], //临邑2 //API返回中未找到该channelId
    dznjzh: [193, 1], //宁津综合
    dzpyzh: [19, 2], //平原综合
    dzqhzh: [251, 8], //齐河综合
    //"dzqyzh": [5, 9], //庆云综合 //ERR_BAD_RESPONSE
    dzqysh: [5, 7], //庆云生活
    //"dzwczh": [33, 4], //武城综合 //ERR_BAD_RESPONSE
    dzwczy: [33, 6], //武城综艺影视
    dzxjzh: [223, 1], //夏津综合
    dzxjgg: [223, 2], //夏津公共
    dzyczh: [235, 1], //禹城综合
    dzyczy: [235, 3], //禹城综艺
    //菏泽
    hzcwzh: [131, 1], //成武综合
    hzcwzy: [131, 2], //成武综艺
    hzcxzh: [87, 2], //曹县综合
    hzdmxw: [111, 2], //东明新闻综合
    hzdt1: [27, 7], //定陶新闻
    hzdt2: [27, 8], //定陶综艺
    hzjczh: [141, 186], //鄄城综合
    //"hzjyxw": [139, 1], //巨野新闻 //ERR_BAD_REQUEST
    hzmdxw: [219, 6], //牡丹区新闻综合
    hzmdzy: [219, 17], //牡丹区综艺
    hzsxzh: [155, 2], //单县综合
    hzycxw: [135, 3], //郓城新闻
    hzyczy: [135, 2], //郓城综艺
    //济宁
    jijiazh: [273, 1], //嘉祥综合
    jijiash: [273, 3], //嘉祥生活
    //"jijxzh": [129, 2], //金乡综合 //ERR_BAD_RESPONSE
    jijxsh: [129, 4], //金乡生活
    jilszh: [89, 1], //梁山综合
    jiqfxw: [13, 1], //曲阜新闻综合
    //"jircxw": [73, 8], //任城新闻综合 //ERR_BAD_RESPONSE
    jircys: [73, 9], //任城影视娱乐
    jissxw: [117, 5], //泗水新闻综合
    jisswh: [117, 6], //泗水文化生活
    //"jiws1": [53, 4], //微山综合 //ERR_BAD_RESPONSE
    //"jiws2": [53, 5], //微山2套 //ECONNABORTED
    jiwszh: [301, 1], //汶上综合
    //"jiwssh": [301, 3], //汶上生活 //API返回中未找到该channelId
    jiytxw: [63, 5], //鱼台新闻
    jiytsh: [63, 15], //鱼台生活
    jiyzxw: [231, 1], //兖州新闻
    //"jiyzsh": [231, 3], //兖州生活 //ERR_BAD_RESPONSE
    jizczh: [181, 1], //邹城综合
    jizcwh: [181, 4], //邹城文化生活
    //聊城
    lccpzh: [31, 6], //茌平综合
    lccpsh: [31, 8], //茌平生活
    //"lcdczh": [265, 1], //东昌综合 //ERR_BAD_RESPONSE
    lcdezh: [95, 22], //东阿综合
    //"lcdezy": [95, 29], //东阿综艺 //ERR_BAD_RESPONSE
    lcgtzh: [43, 1], //高唐综合
    lcgtzy: [43, 5], //高唐综艺
    //"lcgxzh": [79, 1], //冠县综合 //ERR_BAD_RESPONSE
    lclqzh: [65, 2], //临清综合
    lclqjj: [65, 5], //临清经济信息
    //"lcsxzh": [183, 1], //莘县综合 //ERR_BAD_RESPONSE
    lcsxsh: [183, 5], //莘县生活
    lcygzh: [81, 1], //阳谷综合
    //"lcygys": [81, 10], //阳谷影视 //ERR_BAD_RESPONSE
    //临沂
    //"lyzh": [145, 39], //临沂综合 //API返回中未找到该channelId
    //"lygg": [145, 41], //临沂公共 //ERR_BAD_RESPONSE
    //"lynk": [145, 43], //临沂农科 //API返回中未找到该channelId
    lyfxzh: [41, 119], //费县综合
    lyfxsh: [41, 117], //费县生活
    //"lyhdys": [191, 1], //河东影视 //ERR_BAD_RESPONSE
    //"lyhdzh": [191, 2], //河东综合 //ERR_BAD_RESPONSE
    lyjnzh: [105, 4], //莒南综合
    //"lyjnys": [105, 5], //莒南影视 //ERR_BAD_RESPONSE
    //"lyllzh": [113, 1], //兰陵综合 //API返回中未找到该channelId
    //"lyllgg": [113, 2], //兰陵公共 //API返回中未找到该channelId
    //"lylszh": [201, 1], //兰山综合 //ERR_BAD_RESPONSE
    lyls1: [167, 3], //临沭综合
    lyls2: [167, 4], //临沭生活
    //"lylzzh": [147, 1], //罗庄综合 //ERR_BAD_RESPONSE
    //"lylzys": [147, 17], //罗庄影视 //ERR_BAD_RESPONSE
    lymy1: [161, 13], //蒙阴综合
    lymy2: [161, 15], //蒙阴2套
    //"lypyzh": [345, 4], //平邑综合 //ERR_BAD_RESPONSE
    //"lypysh": [345, 14], //平邑生活 //ERR_BAD_REQUEST
    lytc1: [83, 1], //郯城综合
    lytc2: [83, 2], //郯城2套
    //"lyynzh": [177, 6], //沂南综合 //ERR_BAD_RESPONSE
    lyynys: [177, 7], //沂南红色影视
    //"lyys1": [145, 1], //沂水综合 //ERR_BAD_RESPONSE
    lyys2: [145, 2], //沂水生活
    //日照
    rzjx1: [159, 23], //莒县综合
    //"rzjx2": [159, 27], //莒县2套 //ERR_BAD_RESPONSE
    rzls: [289, 1], //岚山TV
    //"rzwlzh": [299, 10], //五莲综合 //ERR_BAD_RESPONSE
    rzwlwh: [299, 12], //五莲文化旅游
    //泰安
    tadpzh: [187, 9], //东平综合
    tadpms: [187, 11], //东平民生
    //"tady": [293, 1], //岱岳TV //ERR_BAD_RESPONSE
    //"tafczh": [51, 3], //肥城综合 //API返回中未找到该channelId
    tafcsh: [51, 6], //肥城生活
    tany1: [123, 1], //宁阳综合
    tany2: [123, 7], //宁阳2套
    tats: [263, 1], //泰山TV
    taxtzh: [59, 2], //新泰综合
    //"taxtxc": [59, 3], //新泰乡村 //ERR_BAD_RESPONSE
    //威海
    whxwzh: [157, 1], //威海新闻综合
    //"whdssh": [157, 3], //威海都市生活 //ERR_BAD_RESPONSE
    //"whhy": [157, 12], //威海海洋 //ERR_BAD_REQUEST
    whhczh: [213, 5], //威海环翠综合
    whrczh: [77, 10], //荣成综合
    whrcsh: [77, 11], //荣成生活
    whrszh: [143, 8], //乳山综合
    whrssh: [143, 9], //乳山生活
    whwd1: [91, 7] //文登TV1
    //"whwd2": [91, 8], //文登TV2 //ERR_BAD_RESPONSE
  }
  var tvInfo = urlRequest('https://app.litenews.cn/v1/app/play/tv/live?orgid=' + tvIdList[id][0]);
  tvInfo = JSON.parse(tvInfo).data
  for (var i in tvInfo) {
    if (tvIdList[id][1] === tvInfo[i].id) {
      return [tvInfo[i].stream]
    }
  }
  return []
}

// 江苏无锡 jswx://xxx
function jswxParser(originUrl) {
  var ids = {
    4: '新闻综合频道',
    9: '都市资讯频道',
    8: '娱乐频道',
    11: '经济频道',
    10: '生活频道'
  }
  var id = originUrl.replace("jswx://", "");
  var cdnConfig = urlRequest('http://bb-share.wifiwx.com/wxbb/share/live/');
  var startIndex = cdnConfig.indexOf("window['LIVE_CHANNELS' ] =") + "window['LIVE_CHANNELS' ] =".length;
  var endIndex = cdnConfig.indexOf(";", startIndex);
  var liveChannelsString = cdnConfig.slice(startIndex, endIndex);
  var liveChannels = JSON.parse(liveChannelsString);
  for (var k in liveChannels) {
    if (liveChannels[k].id == id) {
      return [liveChannels[k].m3u8]
    }
  }
  return []
}

// cctvh5://cctv1//1
function cctvh5Parser(originUrl) {
  var claritylist = ["lg", "cq", "gq"];
  var parts = originUrl.split("//");
  var id = parts[1];
  var idx = Number(parts[2]) || 0;
  var q = claritylist[idx] || "lg";
  var channels = {
    cctv1: "11200132825562653886",
    cctv2: "12030532124776958103",
    cctv4: "10620168294224708952",
    cctv7: "8516529981177953694",
    cctv9: "7252237247689203957",
    cctv10: "14589146016461298119",
    cctv12: "13180385922471124325",
    cctv13: "16265686808730585228",
    cctv17: "4496917190172866934",
    cctv4k: "2127841942201075403"
  };
  var articleId = channels[id];
  if (!articleId) return [];
  var t = Math.floor(Date.now() / 1000);
  var sail = getMD5("articleId=" + articleId + "&scene_type=6");
  var w = "&&&20000009&" + sail + "&" + t + "&emas.feed.article.live.detail&1.0.0&&&&&";
  var sign = CryptoJS.HmacSHA256(w, "emasgatewayh5").toString();
  var clientId = getMD5(String(t));
  var url = "https://emas-api.cctvnews.cctv.com/h5/emas.feed.article.live.detail/1.0.0?articleId=" + articleId + "&scene_type=6";
  var header = [
    { header: "cookieuid", value: clientId },
    { header: "from-client", value: "h5" },
    { header: "Referer", value: "https://m-live.cctvnews.cctv.com/" },
    { header: "x-emas-gw-appkey", value: "20000009" },
    { header: "x-emas-gw-pv", value: "6.1" },
    { header: "x-emas-gw-sign", value: sign },
    { header: "x-emas-gw-t", value: String(t) },
    { header: "x-req-ts", value: String(t * 1000) }
  ];
  var data = urlRequestWithParaAndCoding(url, header, "utf-8");
  var responseJson = base64decode(JSON.parse(data).response);
  var d = JSON.parse(responseJson).data;
  var authResult = d.live_room.liveCameraList[0].pullUrlList[0].authResultUrl[0];
  var authUrl;
  if (q === "lg") {
    authUrl = authResult.authUrl;
  } else if (q === "cq") {
    authUrl = authResult.demote_urls[1].authUrl;
  } else {
    authUrl = authResult.demote_urls[0].authUrl;
  }
  var key = d.dk.substring(0, 8) + String(t).slice(-8);
  var iv = d.dk.slice(-8) + String(t).substring(0, 8);
  var decrypted = CryptoJS.AES.decrypt(authUrl, CryptoJS.enc.Utf8.parse(key), {
    iv: CryptoJS.enc.Utf8.parse(iv),
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.Pkcs7
  });
  var playurl = decrypted.toString(CryptoJS.enc.Utf8);
  return [playurl];
}