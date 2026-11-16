<script setup lang="ts">
import { computed } from 'vue'
import { type Image, src, srcset } from '@/images'

const props = defineProps<{
  image: Image | null
  name: string
  // Across, in CSS pixels
  size: number
}>()

// Without a logo, its first letters, as a person's initials
const initials = computed(() => props.name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => word[0]).join('').toUpperCase())
</script>

<!-- An organisation's logo, whole, on white in a rounded square, as a thing's picture is (pictures.scss), or else its
     first letters; beside its name, so it says nothing more -->
<template>
  <span class="logo" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
    <img v-if="image" :src="src(image, size * 2)" :srcset="srcset(image)" :sizes="`${size}px`" alt="" loading="lazy">
    <span v-else class="initials" :style="{ fontSize: `${Math.round(size * 0.34)}px` }">{{ initials }}</span>
  </span>
</template>

<style scoped lang="scss">
@use '../assets/pictures';

.logo {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  @include pictures.picture($small: true);
  background: #FFFFFF;

  img {
    display: block;
    width: 84%;
    height: 84%;
    object-fit: contain;
  }
}

.initials {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: pictures.$empty;
  font-weight: 600;
  color: var(--muted);
  user-select: none;
}
</style>
