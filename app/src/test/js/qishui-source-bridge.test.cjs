// 只验证洛雪脚本协议、汽水别名和附加资源，不涉及 UI。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const preload = fs.readFileSync(path.join(__dirname, '../../main/assets/script/user-api-preload.js'), 'utf8')

async function verifyRoute(declaredKeys, requestedSource, action, expectedSource) {
  const responses = []
  const calls = []
  const sandbox = {
    console: { log() {} },
    __lx_native_call__(key, event, data) { responses.push({ event, data: JSON.parse(data) }) },
  }
  for (const name of ['set_timeout', 'utils_str2b64', 'utils_b642buf', 'utils_str2md5', 'utils_aes_encrypt', 'utils_rsa_encrypt']) {
    sandbox['__lx_native_call__' + name] = () => ''
  }
  const context = vm.createContext(sandbox)
  vm.runInContext(preload, context)
  vm.runInContext("lx_setup('test-key', 'test-id', '测试音源', '', '1', '', '', '')", context)
  const sources = Object.fromEntries(declaredKeys.map(key => [key, {
    type: 'music', actions: ['musicUrl', 'lyric', 'pic'], qualitys: ['128k', '320k'],
  }]))
  await sandbox.lx.send('inited', { sources })
  const init = responses.find(response => response.event === 'init').data
  assert.equal(init.status, true)
  assert.deepEqual(Object.keys(init.info.sources), declaredKeys)
  for (const key of declaredKeys) {
    assert.deepEqual(init.info.sources[key].qualitys, ['128k', '320k'])
    assert.deepEqual(init.info.sources[key].actions, ['musicUrl', 'lyric', 'pic'])
  }

  await sandbox.lx.on('request', async request => {
    calls.push(request)
    if (request.action === 'lyric') return { lyric: '[00:00.00]测试歌词' }
    return 'https://cdn.example/resource'
  })
  const musicInfo = { id: '7146240707408168993', source: requestedSource, vid: 'test-video', bit_rates: [{ quality: 'higher' }] }
  const info = action === 'musicUrl' ? { type: '320k', musicInfo } : musicInfo
  sandbox.__lx_native__('test-key', 'request', JSON.stringify({
    requestKey: 'request-id', data: { source: requestedSource, action, info },
  }))
  // 桥接通过 Promise 异步回报，不使用它替换过的 setTimeout。
  await new Promise(resolve => setImmediate(resolve))

  const request = calls[0]
  assert.equal(request.source, expectedSource)
  assert.equal(request.action, action)
  const forwarded = action === 'musicUrl' ? request.info.musicInfo : request.info
  assert.equal(forwarded.source, expectedSource)
  assert.equal(forwarded.id, musicInfo.id)
  assert.equal(forwarded.vid, musicInfo.vid)
  assert.deepEqual(JSON.parse(JSON.stringify(forwarded.bit_rates)), musicInfo.bit_rates)
  if (action === 'musicUrl') assert.equal(request.info.type, '320k')
  assert.equal(musicInfo.source, requestedSource)

  const result = responses.find(response => response.event === 'response').data
  assert.equal(result.status, true)
  assert.equal(result.requestKey, 'request-id')
  assert.equal(result.result.source, requestedSource)
  if (action === 'lyric') assert.equal(result.result.data.lyric, '[00:00.00]测试歌词')
  else assert.equal(result.result.data.url, 'https://cdn.example/resource')
}

;(async () => {
  let count = 0
  for (const action of ['musicUrl', 'lyric', 'pic']) {
    for (const [declared, requested, expected] of [
      [['qs'], 'qs', 'qs'],
      [['qishui'], 'qs', 'qishui'],
      [['qs'], 'qishui', 'qs'],
      [['qs', 'qishui'], 'qs', 'qs'],
      [['kw'], 'kw', 'kw'],
    ]) {
      await verifyRoute(declared, requested, action, expected)
      count++
    }
  }
  console.log(`洛雪桥接协议验证通过：${count} 个汽水别名与原音源场景`)
})().catch(error => {
  console.error(error)
  process.exitCode = 1
})
