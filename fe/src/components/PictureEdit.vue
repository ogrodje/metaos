<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId } from 'vue'
import { faArrowUpFromBracket } from '@fortawesome/free-solid-svg-icons'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import RemoveAsked from '@/components/RemoveAsked.vue'
import type { Image } from '@/images'
import { IMAGE_TYPES, type PictureOf, type Progress, removePicture, stopped, uploadError, uploadPicture } from '@/uploads'
import { useLocale } from '@/useLocale'

// A person's avatar or an organisation's logo, the team's to change: Upload (or Change) a picture, PNG, JPEG, WebP or
// GIF of any size, which goes straight to MetaOS's bucket (uploads.ts), and Remove, asked first
const props = defineProps<{
  of: PictureOf
  image: Image | null
}>()

const emit = defineEmits<{
  // It was uploaded or removed
  changed: []
}>()

const { t } = useLocale()
const id = useId()

const kind = computed(() => ('person' in props.of ? 'avatar' : 'logo'))

const input = ref<HTMLInputElement>()
const progress = ref<Progress>()
const error = ref<string>()
let controller: AbortController | undefined

onBeforeUnmount(() => controller?.abort())

async function picked(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (input.value) input.value.value = ''
  if (!file) return
  error.value = undefined
  controller = new AbortController()
  try {
    await uploadPicture(props.of, file, (it) => { progress.value = it }, controller.signal)
    emit('changed')
  } catch (e) {
    if (!stopped(e)) error.value = t('pictureEdit.failed', { name: file.name, error: uploadError(e, t) })
  } finally {
    progress.value = undefined
    controller = undefined
  }
}

const busy = computed(() => !!progress.value)
</script>

<template>
  <div class="picture-edit">
    <input :id="`${id}-file`" ref="input" type="file" class="visually-hidden" :accept="IMAGE_TYPES.join(',')" tabindex="-1"
           @change="picked">
    <div class="actions">
      <button type="button" class="text-button" :aria-disabled="busy || undefined" :aria-describedby="`${id}-formats`"
              @click="busy || input?.click()">
        <FontAwesomeIcon :icon="faArrowUpFromBracket"/>{{ image ? t(`pictureEdit.${kind}.change`) : t(`pictureEdit.${kind}.upload`) }}
      </button>
      <RemoveAsked v-if="image && !busy" :ask="t(`pictureEdit.${kind}.ask`)" :remove="() => removePicture(of)" @removed="emit('changed')"/>
    </div>
    <p :id="`${id}-formats`" class="formats">{{ t('pictureEdit.formats') }}</p>
    <p v-if="progress" class="progress" role="status">
      {{ progress.state === 'uploading' ? t('pictureEdit.uploading', { percent: progress.percent }) : t('pictures.upload.publishing') }}
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped lang="scss">
.picture-edit {
  display: grid;
  gap: 2px;
  font-size: var(--text-caption);
  line-height: 16px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: start;
  gap: 4px 12px;
}

.text-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-caption);
  color: var(--secondary);

  &:hover {
    color: var(--ink);
  }

  &[aria-disabled='true'] {
    color: var(--muted);
    cursor: default;
  }

  svg {
    width: 12px;
    height: 12px;
  }
}

.formats {
  color: var(--muted);
}

.progress {
  color: var(--secondary);
  font-variant-numeric: tabular-nums;
}

.error {
  color: var(--accent-text);
}
</style>
