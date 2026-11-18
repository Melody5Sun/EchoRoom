<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NButton, NInputNumber, NModal, NSpace, NTag, NSpin } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import { giftApi } from '@/api'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useWalletStore } from '@/stores/wallet'
import Icon from './Icon.vue'
import type { GiftCatalog, GiftOrder } from '@/types/api'

const props = defineProps<{ roomId: number; enabled?: boolean }>()
const messageApi = inject<any>('message')
const router = useRouter()
const route = useRoute()
const wallet = useWalletStore()
const auth = useAuthStore()
const selected = ref<GiftCatalog | null>(null)
const quantity = ref(1)
const showRecharge = ref(false)
const rechargeAmount = ref(1000)
const sending = ref(false)
const loading = ref(true)
const error = ref('')
const pendingGift = ref<{ roomId: number; giftCode: string; quantity: number; clientOrderNo: string; uncertain: boolean } | null>(null)
let generation = 0
watch(() => [props.roomId, auth.userId, props.enabled], () => {
  generation += 1
  pendingGift.value = null
  sending.value = false
})
onBeforeUnmount(() => { generation += 1 })

onMounted(load)
watch(() => props.enabled, (enabled) => {
  if (enabled !== false) void wallet.loadBalance().catch(() => { wallet.wallet = null })
  else wallet.wallet = null
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    await wallet.loadCatalog()
    if (props.enabled !== false) await wallet.loadBalance()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '礼物目录加载失败'
  } finally {
    loading.value = false
  }
}

async function requireLogin() {
  if (props.enabled !== false) return true
  await router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

async function send() {
  if (sending.value || (!pendingGift.value && !selected.value)) return
  sending.value = true
  const attempt = generation
  const current = () => attempt === generation
  try {
    if (!(await requireLogin()) || !current()) return
    const retry = pendingGift.value !== null
    const pending = pendingGift.value ?? {
      roomId: props.roomId, giftCode: selected.value!.code, quantity: quantity.value,
      clientOrderNo: `gift-${Date.now()}-${crypto.randomUUID().slice(0, 8)}`, uncertain: false,
    }
    pendingGift.value = pending
    let order: GiftOrder | undefined
    if (retry) {
      try { order = await giftApi.order(pending.clientOrderNo) } catch (err) {
        if (!(err instanceof ApiError) || err.status !== 404) throw err
      }
      if (!current()) return
    }
    if (!order) {
      try {
        order = await giftApi.send(pending.roomId, {
          giftCode: pending.giftCode, quantity: pending.quantity, clientOrderNo: pending.clientOrderNo,
        })
        // Acceptance commits us to this identity until a terminal result is known.
        pending.uncertain = true
      } catch (err) {
        if (!current()) return
        // A definitive first rejection cannot have committed. An earlier uncertain
        // attempt must keep its identity even if a later retry is rejected.
        if (!pending.uncertain && err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 408) {
          pendingGift.value = null
          throw err
        }
        pending.uncertain = true
      }
    }
    if (!current()) return
    if (order && await finishOrder(order, current)) return
    for (let i = 0; i < 8; i += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 800))
      if (!current()) return
      try { order = await giftApi.order(pending.clientOrderNo) } catch (err) {
        if (err instanceof ApiError && err.status === 404) continue
        throw err
      }
      if (!current()) return
      if (await finishOrder(order, current)) return
    }
    messageApi?.warning('订单结果尚未确认，请核实上次订单，或在个人中心查询')
  } catch (err) {
    if (!current()) return
    if (pendingGift.value) {
      pendingGift.value.uncertain = true
      messageApi?.warning('订单结果尚未确认，请核实上次订单，或在个人中心查询')
    } else messageApi?.error(err instanceof Error ? err.message : '送礼失败')
  } finally {
    if (current()) sending.value = false
  }
}

async function finishOrder(order: GiftOrder, current: () => boolean) {
  if (!current() || !['SUCCESS', 'FAILED', 'CLOSED'].includes(order.status)) return false
  pendingGift.value = null
  messageApi?.[order.status === 'SUCCESS' ? 'success' : 'error'](order.status === 'SUCCESS' ? '礼物订单处理成功' : order.failureReason || '礼物订单处理失败')
  try { await wallet.loadBalance() } catch { /* A balance refresh does not change a settled order. */ }
  return true
}

async function recharge() {
  if (!(await requireLogin())) return
  try {
    await wallet.recharge(rechargeAmount.value)
    showRecharge.value = false
    messageApi?.success('金币充值成功')
  } catch (err) {
    messageApi?.error(err instanceof Error ? err.message : '充值失败')
  }
}
</script>

<template>
  <div class="gift-panel surface">
    <div class="panel-head"><div><strong>支持主播</strong><div class="panel-caption">用虚拟金币送出礼物</div></div><NButton text size="small" @click="showRecharge = true">充值金币</NButton></div>
    <div v-if="enabled !== false" class="balance-row"><span>我的余额</span><strong>{{ wallet.wallet?.balance ?? '—' }}</strong><span class="coin">金币</span></div>
    <NSpin v-if="loading" size="small" class="gift-loading" />
    <div v-else-if="error" class="gift-error"><span>{{ error }}</span><NButton text size="small" @click="load">重试</NButton></div>
    <div v-else class="gift-list"><button v-for="gift in wallet.gifts" :key="gift.code" class="gift-option" :class="[`gift-${gift.code}`, { selected: selected?.code === gift.code }]" :disabled="enabled === false || sending || !!pendingGift" @click="selected = gift"><span class="gift-icon"><Icon name="gift" :size="24" /></span><span>{{ gift.name }}</span><small>{{ gift.price }} 金币</small></button></div>
    <div class="gift-actions"><NInputNumber v-model:value="quantity" :min="1" :max="99" size="small" :disabled="enabled === false || sending || !!pendingGift" /><NButton type="primary" :loading="sending" :disabled="(!selected && !pendingGift) || enabled === false" @click="send">{{ enabled === false ? '登录后送礼' : pendingGift ? '核实上次订单' : `送出 ${selected?.name || '礼物'}` }}</NButton></div>
    <NTag v-if="pendingGift" type="warning" size="small">请先核实上次订单；重试会沿用同一订单号</NTag>
    <NTag v-if="enabled === false" type="default" size="small">登录后即可送礼</NTag>
    <NModal v-model:show="showRecharge" preset="card" title="充值虚拟金币" style="width: min(380px, calc(100vw - 32px))"><NSpace vertical><p class="muted">这是项目演示用的虚拟充值，不会产生真实扣款。</p><NInputNumber v-model:value="rechargeAmount" :min="1" :step="100" /><NButton type="primary" block @click="recharge">确认充值</NButton></NSpace></NModal>
  </div>
</template>

<style scoped>
.gift-panel { padding: 18px; }
.panel-head { display: flex; align-items: start; justify-content: space-between; gap: 10px; }
.panel-caption { margin-top: 4px; color: var(--sh-muted); font-size: 12px; }
.balance-row { display: flex; align-items: baseline; gap: 7px; margin: 18px 0 14px; color: var(--sh-muted); font-size: 12px; }
.balance-row strong { margin-left: auto; color: var(--sh-ink); font-size: 23px; }
.coin { color: var(--sh-primary); }
.gift-loading { display: block; margin: 24px auto; }
.gift-error { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 20px 0; color: var(--sh-danger); font-size: 13px; }
.gift-list { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.gift-option { display: flex; align-items: center; flex-direction: column; gap: 4px; min-height: 94px; padding: 10px 6px; border: 1px solid var(--sh-border); border-radius: 10px; color: var(--sh-ink); background: var(--sh-surface-muted); cursor: pointer; }
.gift-option:hover, .gift-option.selected { border-color: var(--sh-primary); background: var(--sh-primary-soft); }
.gift-option:disabled { cursor: not-allowed; opacity: .62; }
.gift-option small { color: var(--sh-primary); }
.gift-icon { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; color: var(--sh-primary); background: #dcebe3; }
.gift-rocket .gift-icon { color: #466a9a; background: #e3ebf7; }
.gift-crown .gift-icon { color: #9a6a2a; background: #f2e8d4; }
.gift-actions { display: grid; grid-template-columns: 90px 1fr; gap: 8px; margin-top: 14px; }
.gift-panel > .n-tag { margin-top: 12px; }
</style>
