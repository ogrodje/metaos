<script setup lang="ts">
import { RouterLink } from 'vue-router'
import PersonAvatar from '@/components/PersonAvatar.vue'
import type { Image } from '@/images'
import { personPath } from '@/people'

// Someone in a line: their picture, their name, which leads to their page, and what they are, e.g. host
defineProps<{
  id: string
  name: string
  avatar?: Image | null
  role?: string
}>()
</script>

<template>
  <div class="person-line">
    <PersonAvatar :image="avatar ?? null" :name="name" :size="24"/>
    <RouterLink class="name" :to="personPath({ id })">{{ name }}</RouterLink>
    <span v-if="role" class="role">{{ role }}</span>
  </div>
</template>

<style scoped lang="scss">

.person-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-decoration: underline;
  text-decoration-color: #C4C4C4;
  text-underline-offset: 3px;

  &:hover {
    text-decoration-color: currentColor;
  }
}

.role {
  margin-left: auto;
  padding-left: 8px;
  color: var(--muted);
  white-space: nowrap;
}
</style>
