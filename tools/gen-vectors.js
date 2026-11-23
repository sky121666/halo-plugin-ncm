// 用上游代码生成对比测试样本（固定随机数）
// 用法：
//   git clone https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced /tmp/api-enhanced
//   cd /tmp/api-enhanced && pnpm install
//   node tools/gen-vectors.js /tmp/api-enhanced > src/test/resources/vectors.json
const path = require('path')
const root = process.argv[2]
if (!root) {
  console.error('用法: node tools/gen-vectors.js <api-enhanced 目录>')
  process.exit(1)
}
const nodeCrypto = require('crypto')
const { publicKey: serverPub } = nodeCrypto.generateKeyPairSync('x25519')
const serverPubRaw = Buffer.from(serverPub.export({ format: 'der', type: 'spki' })).subarray(-32)

// 固定随机数：weapi 的 secretKey 变成 16 个 'a'；xeapi 的动态密钥、midTransform 随机串、GCM iv 全为 0
Math.random = () => 0
nodeCrypto.randomBytes = (n) => Buffer.alloc(n)

const req = (p) => require(path.join(path.resolve(root), p))
const enc = req('util/crypto.js')
const CryptoJS = req('node_modules/crypto-js')

const cases = [
  { uri: '/api/v1/album/123', data: { e_r: false } },
  { uri: '/api/v6/playlist/detail', data: { id: '24381616', n: 100000, s: 8, e_r: false } },
  { uri: '/api/search/get', data: { s: '周杰伦 晴天', type: 1, limit: 30, offset: 0, e_r: false } },
  { uri: '/api/song/lyric/v1', data: { id: '186016', cp: false, tv: 0, lv: 0, e_r: false } },
]
const out = { weapi: [], eapi: [], linuxapi: [], xeapi: [], xeapiSign: [], encodeId: [] }
for (const c of cases) {
  const input = JSON.stringify(c.data)
  out.weapi.push({ input, secretKey: 'aaaaaaaaaaaaaaaa', ...enc.weapi(c.data) })
  out.eapi.push({ url: c.uri, input, ...enc.eapi(c.uri, c.data) })
  out.linuxapi.push({ input, ...enc.linuxapi(c.data) })
}

const xeCases = [
  { uri: '/api/song/enhance/player/url/v1', data: { ids: '[186016]', level: 'standard', encodeType: 'flac', e_r: false } },
  { uri: '/api/register/anonimous', data: { username: 'dGVzdA==', e_r: false } },
  { uri: '/api/search/get', data: { s: '周杰伦 晴天', type: 1, limit: 30, e_r: false } },
]
const state = { publicKey: serverPubRaw.toString('base64'), version: '3', sk: 'test-sk' }
for (const c of xeCases) {
  const r = enc.xeapi(c.uri, { ...c.data }, { publicKeyState: state })
  out.xeapi.push({ uri: c.uri, data: c.data, ...state, C: r.C, R: r.R })
}
out.xeapiSign.push({ timestamp: '1700000000000', nonce: '1234567890123456',
  signature: enc.xeapiSign('1700000000000', '1234567890123456') })

// register_anonimous 里的 cloudmusic_dll_encode_id（上游未导出，这里原样复制）
const ID_XOR_KEY_1 = '3go8&$8*3*3h0k(2)2'
const encodeId = (id) => {
  let x = ''
  for (let i = 0; i < id.length; i++) x += String.fromCharCode(id.charCodeAt(i) ^ ID_XOR_KEY_1.charCodeAt(i % ID_XOR_KEY_1.length))
  return CryptoJS.enc.Base64.stringify(CryptoJS.MD5(CryptoJS.enc.Utf8.parse(x)))
}
for (const id of ['0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123', 'FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF']) {
  out.encodeId.push({ deviceId: id, encoded: encodeId(id) })
}
// util/ncbl.js：RSA 包装和 ChaCha20（scrobble_v1 用）
const ncbl = req('util/ncbl.js')
const keyA = Buffer.from('0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20', 'hex')
const nonce = Buffer.from('000102030405060708090a0b', 'hex')
out.ncbl = {
  keyA: keyA.toString('hex'),
  keyB: ncbl.rsaWrap(Buffer.from(keyA)).toString('hex'),
  chacha: { key: keyA.toString('hex'), counter: 7, nonce: nonce.toString('hex'), plain: '68656c6c6f206e63626c',
    cipher: ncbl.chacha20(keyA, 7, nonce, Buffer.from('hello ncbl')).toString('hex') },
}
console.log(JSON.stringify(out, null, 2))
