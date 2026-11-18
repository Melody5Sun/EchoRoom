import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import GiftPanel from './GiftPanel.vue'

const mocks = vi.hoisted(() => ({ send: vi.fn(), order: vi.fn(), balance: vi.fn(), catalog: vi.fn() }))
vi.mock('@/api', () => ({ giftApi: { send: mocks.send, order: mocks.order } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ userId: 7 }) }))
vi.mock('@/stores/wallet', () => ({ useWalletStore: () => ({
  gifts: [{ id: 1, code: 'flower', name: '鲜花', price: 1, status: 'ACTIVE' }],
  loadCatalog: mocks.catalog, loadBalance: mocks.balance, wallet: { balance: 100 },
}) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ fullPath: '/live/1' }), useRouter: () => ({ push: vi.fn() }) }))

function mount() {
  const message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
  const wrapper = shallowMount(GiftPanel, { props: { roomId: 1, enabled: true }, global: { renderStubDefaultSlot: true, provide: { message } } })
  return { wrapper, message }
}
type State = { send: () => Promise<void>; pendingGift: { clientOrderNo: string } | null; sending: boolean }
const terminal = (orderNo: string) => ({ orderNo, status: 'SUCCESS' })

describe('GiftPanel uncertain order recovery', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mocks.catalog.mockResolvedValue(undefined)
    mocks.balance.mockResolvedValue(undefined)
  })
  afterEach(() => { vi.useRealTimers() })

  it('recovers a committed order after 503 without creating another order', async () => {
    const { wrapper, message } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    mocks.send.mockRejectedValueOnce(new ApiError('timeout', 'HTTP-503', 503))
    mocks.order.mockImplementation(async (id: string) => terminal(id))
    const task = state.send()
    await vi.advanceTimersByTimeAsync(800)
    await task
    expect(mocks.send).toHaveBeenCalledTimes(1)
    expect(mocks.order).toHaveBeenCalledWith(mocks.send.mock.calls[0][1].clientOrderNo)
    expect(state.pendingGift).toBeNull()
    expect(message.error).not.toHaveBeenCalled()
    expect(message.success).toHaveBeenCalledWith('礼物订单处理成功')
    wrapper.unmount()
  })

  it('keeps the same payload and order number when retry lookup returns 404', async () => {
    const { wrapper } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    mocks.send.mockRejectedValueOnce(new ApiError('timeout', 'HTTP-503', 503))
    mocks.order.mockRejectedValue(new ApiError('unavailable', 'HTTP-503', 503))
    const first = state.send()
    await vi.advanceTimersByTimeAsync(800)
    await first
    expect(state.pendingGift).not.toBeNull()
    expect(wrapper.text()).toContain('核实上次订单')
    expect(wrapper.get('.gift-option').attributes('disabled')).toBeDefined()
    mocks.order.mockRejectedValueOnce(new ApiError('not found', 'HTTP-404', 404))
    mocks.send.mockImplementationOnce(async (_room: number, body: { clientOrderNo: string }) => terminal(body.clientOrderNo))
    await state.send()
    expect(mocks.send).toHaveBeenCalledTimes(2)
    expect(mocks.send.mock.calls[1]).toEqual(mocks.send.mock.calls[0])
    expect(state.pendingGift).toBeNull()
    wrapper.unmount()
  })

  it('prevents double clicks and permits a fresh order after a known terminal result', async () => {
    const { wrapper } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    let resolve!: (value: unknown) => void
    mocks.send.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const first = state.send()
    await flushPromises()
    await state.send()
    expect(mocks.send).toHaveBeenCalledTimes(1)
    resolve(terminal(mocks.send.mock.calls[0][1].clientOrderNo))
    await first
    mocks.send.mockImplementationOnce(async (_room: number, body: { clientOrderNo: string }) => terminal(body.clientOrderNo))
    await state.send()
    expect(mocks.send.mock.calls[1][1].clientOrderNo).not.toBe(mocks.send.mock.calls[0][1].clientOrderNo)
    wrapper.unmount()
  })

  it('discards a late result after the room changes', async () => {
    const { wrapper, message } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    let resolve!: (value: unknown) => void
    mocks.send.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const task = state.send()
    await flushPromises()
    await wrapper.setProps({ roomId: 2 })
    resolve(terminal(mocks.send.mock.calls[0][1].clientOrderNo))
    await task
    expect(state.pendingGift).toBeNull()
    expect(message.success).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('queries an already accepted order after polling fails, without resubmitting', async () => {
    const { wrapper } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    mocks.send.mockImplementationOnce(async (_room: number, body: { clientOrderNo: string }) => ({ orderNo: body.clientOrderNo, status: 'PENDING' }))
    mocks.order.mockRejectedValueOnce(new ApiError('timeout', 'HTTP-503', 503))
    const first = state.send()
    await vi.advanceTimersByTimeAsync(800)
    await first
    const id = state.pendingGift!.clientOrderNo
    mocks.order.mockResolvedValueOnce(terminal(id))
    await state.send()
    expect(mocks.send).toHaveBeenCalledTimes(1)
    expect(mocks.order).toHaveBeenLastCalledWith(id)
    expect(state.pendingGift).toBeNull()
    wrapper.unmount()
  })

  it('releases a definitively rejected first attempt', async () => {
    const { wrapper, message } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    mocks.send.mockRejectedValueOnce(new ApiError('limited', 'RATE_LIMITED', 429))
    await state.send()
    expect(state.pendingGift).toBeNull()
    expect(mocks.order).not.toHaveBeenCalled()
    expect(message.error).toHaveBeenCalledWith('limited')
    wrapper.unmount()
  })

  it('preserves an accepted order identity after polling expires and a later retry is rejected', async () => {
    const { wrapper } = mount()
    await flushPromises()
    await wrapper.get('.gift-option').trigger('click')
    const state = wrapper.vm as unknown as State
    mocks.send.mockImplementationOnce(async (_room: number, body: { clientOrderNo: string }) => ({ orderNo: body.clientOrderNo, status: 'PENDING' }))
    mocks.order.mockImplementation(async (id: string) => ({ orderNo: id, status: 'PENDING' }))
    const first = state.send()
    await vi.advanceTimersByTimeAsync(6400)
    await first
    const id = state.pendingGift!.clientOrderNo
    mocks.order.mockRejectedValueOnce(new ApiError('not found', 'HTTP-404', 404))
    mocks.send.mockRejectedValueOnce(new ApiError('limited', 'RATE_LIMITED', 429))
    const retry = state.send()
    await vi.advanceTimersByTimeAsync(6400)
    await retry
    expect(state.pendingGift!.clientOrderNo).toBe(id)
    expect(mocks.send.mock.calls[1][1].clientOrderNo).toBe(id)
    wrapper.unmount()
  })
})
