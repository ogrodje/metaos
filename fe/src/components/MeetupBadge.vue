<script setup lang="ts">
import { computed } from 'vue'
import { badgeColors, type Meetup } from '@/events'

// Who an event is by, as the Events page shows it: their name in their colours, with their logo; white on a colour from
// their name where they have none
const props = defineProps<{ meetup: Pick<Meetup, 'name' | 'logo' | 'mainColor' | 'backgroundColor'> }>()
const badge = computed(() => badgeColors(props.meetup))
</script>

<template>
  <span class="meetup" :style="{ color: badge.color, background: badge.background }">
    <img v-if="meetup.logo" :src="meetup.logo" alt="" width="20" height="20" loading="lazy">{{ meetup.name }}
  </span>
</template>

<style scoped lang="scss">
.meetup {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  min-height: 24px;
  padding: 2px 8px 2px 2px;
  border-radius: var(--radius-xs);
  font-size: var(--text-caption);
  line-height: 16px;
  font-weight: 600;
  // An edge, for a badge as white as the page
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .08);

  // Without a logo, the name has the badge to itself
  &:not(:has(img)) {
    padding-left: 8px;
  }

  img {
    flex: none;
    width: 20px;
    height: 20px;
    object-fit: cover;
    border-radius: 3px;
    background: #FFFFFF;
  }
}
</style>
