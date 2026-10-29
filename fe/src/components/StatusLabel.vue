<script setup lang="ts">
import { computed } from 'vue'
import { type EpisodeStatus, statusName } from '@/episodes'

const props = defineProps<{ status: EpisodeStatus }>()

// A ring until it's recorded, the logo's pink while it's being made, grey once it's out, on hold or cancelled
const stage = computed(() => {
  switch (props.status) {
    case 'IDEA':
    case 'PLANNED':
    case 'SCHEDULED':
      return 'before'
    case 'RECORDED':
    case 'IN_PRODUCTION':
    case 'READY':
    case 'EARLY_ACCESS':
      return 'making'
    default:
      return 'after'
  }
})
</script>

<template>
  <span class="status" :class="stage"><span class="dot" aria-hidden="true"/>{{ statusName(status) }}</span>
</template>

<style scoped lang="scss">

.status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--text-small);
  line-height: 20px;
  color: var(--secondary);
  white-space: nowrap;
}

.dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--muted);
}

.before .dot {
  background: none;
  box-shadow: inset 0 0 0 1.5px var(--muted);
}

.making .dot {
  background: var(--accent);
}

</style>
