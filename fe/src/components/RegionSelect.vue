<script setup lang="ts">
import { computed } from 'vue'
import { type Region, regionName, REGIONS } from '@/events'
import { useLocale } from '@/useLocale'

// The region the team gives an event or an organisation: Automatic, which is what its place tells (found, said beside
// it), or one of the regions, online or abroad, which then stays whatever its place says
const props = defineProps<{ found?: Region | null }>()

const model = defineModel<Region | ''>({ required: true })
const { t } = useLocale()

const auto = computed(() => (props.found ? t('events.regionAutoFound', { region: regionName(props.found, t) }) : t('events.regionAuto')))
</script>

<template>
  <select v-model="model" class="input">
    <option value="">{{ auto }}</option>
    <option v-for="region in REGIONS" :key="region" :value="region">{{ regionName(region, t) }}</option>
  </select>
</template>
