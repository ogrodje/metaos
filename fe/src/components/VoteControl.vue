<script setup lang="ts">
import { ref } from 'vue'
import { errorMessage, graphql } from '@/graphql'
import { scoreText, toggled, type Vote, VOTE_ON_IDEA, type Votes, votersText } from '@/votes'

// An idea's vote buttons: ▲ its score ▼ stacked, in the Ideas table, or Up and Down with their counts in a row, on its
// page. A click casts that vote, or takes it back when it's yours already; the score names who voted on hover. The
// idea's votes come back with the answer, which the page takes; the live updates bring everyone else's.
const props = defineProps<{
  episodeId: string
  title: string
  votes: Votes
  layout: 'stacked' | 'row'
}>()

const emit = defineEmits<{ voted: [votes: Votes] }>()

const sending = ref(false)
const error = ref<string>()

async function vote(clicked: Exclude<Vote, 'NONE'>) {
  if (sending.value) return
  sending.value = true
  error.value = undefined
  try {
    const answer = await graphql<{ votes: Votes }>(VOTE_ON_IDEA, { ref: props.episodeId, vote: toggled(props.votes.mine, clicked) })
    emit('voted', answer.votes)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <div class="vote-control" :class="layout" :aria-busy="sending">
    <button class="up" type="button" :aria-pressed="votes.mine === 'UP'" :disabled="sending"
            :aria-label="`Vote up ${title}`" @click="vote('UP')">
      <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M6 2l5 7H1z"/></svg>
      <template v-if="layout === 'row'">Up {{ votes.up }}</template>
    </button>
    <span class="score" :class="{ zero: votes.score === 0, mine: votes.mine === 'UP' }" tabindex="0"
          :title="votersText(votes)" :aria-label="`Score ${scoreText(votes.score)}: ${votersText(votes)}`">
      {{ scoreText(votes.score) }}
    </span>
    <button class="down" type="button" :aria-pressed="votes.mine === 'DOWN'" :disabled="sending"
            :aria-label="`Vote down ${title}`" @click="vote('DOWN')">
      <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M6 10L1 3h10z"/></svg>
      <template v-if="layout === 'row'">Down {{ votes.down }}</template>
    </button>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped lang="scss">

.vote-control {
  display: flex;
  align-items: center;
}

button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--muted);
  background: none;
  border: 0;
  border-radius: 6px;
  cursor: pointer;

  svg {
    width: 12px;
    height: 12px;
    fill: currentColor;
  }

  &:hover {
    color: var(--ink);
    background: var(--hover);
  }

  &:disabled {
    cursor: progress;
  }

  // Your vote: up in the logo's pink, down in grey
  &.up[aria-pressed='true'] {
    color: var(--accent-text);
    background: #FDF0F3;
  }

  &.down[aria-pressed='true'] {
    color: #FFFFFF;
    background: var(--secondary);
  }
}

.score {
  font-size: 13px;
  line-height: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  cursor: default;

  &.zero {
    color: var(--muted);
    font-weight: 500;
  }

  &.mine {
    color: var(--accent-text);
  }
}

.error {
  font-size: 12px;
  line-height: 18px;
  color: var(--accent-text);
}

// In the Ideas table: ▲ score ▼ in a column, the buttons as big as an icon link
.stacked {
  flex-direction: column;
  width: 48px;

  button {
    width: var(--icon-link);
    height: 26px;
  }

  .error {
    width: 160px;
  }
}

// On the idea's page: Up and Down with their counts, the score at the end
.row {
  flex-wrap: wrap;
  gap: 8px;

  button {
    height: 36px;
    padding: 0 12px;
    font-size: 13px;
    font-weight: 500;
    font-variant-numeric: tabular-nums;
    color: var(--ink);
    border: 1px solid var(--hairline);
    border-radius: 6.4px;

    &:hover {
      border-color: #A3A3A3;
    }

    &[aria-pressed='true'] {
      border-color: transparent;
    }
  }

  .score {
    order: 3;
    margin-left: auto;
    font-size: 20px;
    line-height: 28px;
  }

  .error {
    order: 4;
    flex-basis: 100%;
  }
}
</style>
