import assert from 'node:assert/strict'
import test from 'node:test'
import { registerSource } from './helpers/onboarding-source.mjs'

const hook = registerSource()
const { ApiClient } = await import('../src/lib/api/client.ts')
const { RemoteDesktopApi, remoteDesktopErrorMessage } = await import('../src/lib/remote-desktop/api.ts')
hook.deregister()

/** Fake fetcher that records calls and answers with a fixed error envelope. */
function failingClient(status, detail) {
  const calls = []
  const client = new ApiClient({
    fetcher: async (url, init) => {
      calls.push({ url, init })
      return new Response(JSON.stringify({ detail }), {
        status,
        headers: { 'content-type': 'application/json' },
      })
    },
  })
  return { api: new RemoteDesktopApi(client), calls }
}

test('409 remote_desktop_busy maps to takeover copy', async () => {
  const { api } = failingClient(409, {
    code: 'remote_desktop_busy',
    message: 'Control is held by another participant.',
  })
  await assert.rejects(
    api.createSession('token', { connectorId: 'conn_x', mode: 'control' }),
    (error) => {
      assert.equal(error.status, 409)
      assert.equal(error.code, 'remote_desktop_busy')
      assert.equal(remoteDesktopErrorMessage(error), '控制权正被其他窗口占用，请稍后再试。')
      return true
    },
  )
})

test('409 remote_desktop_unavailable maps through details.reason', async () => {
  const { api } = failingClient(409, {
    code: 'remote_desktop_unavailable',
    message: 'Remote desktop is not available.',
    details: { reason: 'connector_offline' },
  })
  await assert.rejects(
    api.createSession('token', { connectorId: 'conn_x', mode: 'view' }),
    (error) => {
      assert.equal(error.code, 'remote_desktop_unavailable')
      assert.equal(remoteDesktopErrorMessage(error), '设备离线，暂时无法使用远程桌面。')
      return true
    },
  )
})

test('409 remote_desktop_unavailable without a known reason keeps the base copy', async () => {
  const { api } = failingClient(409, {
    code: 'remote_desktop_unavailable',
    message: 'Remote desktop is not available.',
    details: { reason: 'something_new' },
  })
  await assert.rejects(
    api.takeControl('token', 'rds_1', { participantId: 'pt_1' }),
    (error) => {
      assert.equal(remoteDesktopErrorMessage(error), '这台设备暂时无法使用远程桌面。')
      return true
    },
  )
})

test('429 remote_desktop_limit_reached maps to the session-limit copy', async () => {
  const { api } = failingClient(429, {
    code: 'remote_desktop_limit_reached',
    message: 'Session limit reached.',
  })
  await assert.rejects(
    api.createSession('token', { connectorId: 'conn_x', mode: 'view' }),
    (error) => {
      assert.equal(error.status, 429)
      assert.equal(remoteDesktopErrorMessage(error), '远程桌面会话数已达上限，请稍后再试。')
      return true
    },
  )
})

test('429 remote_desktop_rate_limited maps to the throttle copy', async () => {
  const { api } = failingClient(429, {
    code: 'remote_desktop_rate_limited',
    message: 'Too many screenshots.',
  })
  await assert.rejects(
    api.screenshot('token', 'rds_1', { format: 'jpeg' }),
    (error) => {
      assert.equal(remoteDesktopErrorMessage(error), '操作太频繁，请稍后再试。')
      return true
    },
  )
})

test('503 media_unavailable maps to the media-service copy', async () => {
  const { api } = failingClient(503, {
    code: 'media_unavailable',
    message: 'Media backend unreachable.',
  })
  await assert.rejects(
    api.refreshMediaToken('token', 'rds_1', { participantId: 'pt_1', role: 'viewer' }),
    (error) => {
      assert.equal(error.status, 503)
      assert.equal(remoteDesktopErrorMessage(error), '媒体服务暂时不可用，请稍后再试。')
      return true
    },
  )
})

test('unknown codes fall back to the server message', async () => {
  const { api } = failingClient(500, {
    code: 'weird_future_code',
    message: 'Something exploded.',
  })
  await assert.rejects(
    api.endSession('token', 'rds_1'),
    (error) => {
      assert.equal(remoteDesktopErrorMessage(error), 'Something exploded.')
      return true
    },
  )
})

test('requests go through apiClient with namespaced paths and bearer auth', async () => {
  const { api, calls } = failingClient(404, {
    code: 'remote_desktop_not_found',
    message: 'Not found.',
  })
  await api.heartbeat('tok_1', 'rds_9', { participantId: 'pt_9' }).catch(() => {})
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/api/v2/remote-desktop/sessions/rds_9/heartbeat')
  assert.equal(calls[0].init.method, 'POST')
  assert.equal(calls[0].init.headers.get('authorization'), 'Bearer tok_1')
  assert.deepEqual(JSON.parse(calls[0].init.body), { participantId: 'pt_9' })
})
