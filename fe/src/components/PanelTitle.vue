<script setup lang="ts">
import { type Image, src, srcset } from '@/images'

// What a page is about, at the top of its FactPanel once its title has scrolled away (shown), so the panel says what
// one is looking at or commenting on: a small picture, e.g. an episode's cover, its code and its title. It opens and
// fades in, and back to the top when clicked. Only where the panel stays in view as the page scrolls.
defineProps<{
  title: string
  code?: string | null
  picture?: Image | null
  shown: boolean
}>()

function toTop() {
  const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: smooth ? 'smooth' : 'auto' })
}
</script>

<template>
  <div class="panel-title" :class="{ shown }" :aria-hidden="!shown">
    <div class="inner">
      <button type="button" class="card" :tabindex="shown ? 0 : -1" :title="`Back to the top: ${title}`" @click="toTop">
        <img v-if="picture" :src="src(picture, 320)" :srcset="srcset(picture)" sizes="40px" alt="">
        <span class="text">
          <span v-if="code" class="code">{{ code }}</span>
          <span class="name" lang="sl">{{ title }}</span>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '../assets/pictures';

// Closed, as tall as nothing, until it's shown: then it opens to its height and fades in
.panel-title {
  display: none;
}

@media (min-width: 961px) and (min-height: 720px) {
  .panel-title {
    display: grid;
    grid-template-rows: 0fr;
    opacity: 0;
    transition: grid-template-rows 0.3s ease, opacity 0.3s ease;

    &.shown {
      grid-template-rows: 1fr;
      opacity: 1;
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .panel-title {
    transition: none;
  }
}

.inner {
  min-height: 0;
  overflow: hidden;
}

.card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  width: 100%;
  margin-bottom: 16px;
  padding: 0 0 14px;
  font: inherit;
  text-align: left;
  color: var(--ink);
  background: none;
  border: 0;
  border-bottom: 1px solid var(--rule);
  cursor: pointer;

  &:hover .name {
    color: var(--accent-text);
  }

  img {
    width: 40px;
    height: 40px;
    object-fit: cover;
    @include pictures.picture($small: true);
  }
}

.text {
  display: grid;
  min-width: 0;
}

.code {
  font-family: var(--font-mono);
  font-size: var(--text-caption);
  line-height: 16px;
  color: var(--muted);
}

// Two lines at most
.name {
  display: -webkit-box;
  overflow: hidden;
  font-size: var(--text-small);
  line-height: 19px;
  font-weight: 600;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
</style>
