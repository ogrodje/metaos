<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import MarkedText from '@/components/MarkedText.vue'
import PersonAvatar from '@/components/PersonAvatar.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { episodePath } from '@/episodes'
import { type Person, personPath, rolesOf, socialLinks } from '@/people'

const props = defineProps<{
  person: Person
  // Found by a search: the words to mark
  words?: string[]
}>()

// Their latest episodes by code, and how many more there are: hosts have dozens
const LATEST = 3
const latest = computed(() => props.person.episodes.slice(0, LATEST).map(({ episode }) => episode))
const more = computed(() => props.person.episodes.length - latest.value.length)

const roles = computed(() => rolesOf(props.person))
const hasLinks = computed(() => socialLinks(props.person).length > 0)
</script>

<!-- A person: their picture, their name, which leads to their page, and who they are, then what they've been on the
     episodes and the latest of those, then where they're online. Like a released episode's row (EpisodeItem), the links at the end. -->
<template>
  <li class="person">
    <PersonAvatar class="picture" :image="person.avatar" :name="person.name" :size="40"/>
    <div class="main">
      <p class="title">
        <RouterLink :to="personPath(person)"><MarkedText :text="person.name" :words="words"/></RouterLink>
      </p>
      <p v-if="person.bio" class="summary"><MarkedText :text="person.bio" :words="words"/></p>
      <p v-if="person.episodes.length" class="details">
        <span class="roles">{{ roles }}</span>
        <!-- An episode without a code, e.g. an idea, by its title -->
        <span>
          <template v-for="(episode, i) in latest" :key="episode.id">
            <template v-if="i">, </template>
            <RouterLink :class="{ code: episode.code }" :to="episodePath(episode)" :title="episode.title" lang="sl">{{ episode.code ?? episode.title }}</RouterLink>
          </template>
          <template v-if="more"> and {{ more }} more</template>
        </span>
      </p>
    </div>
    <div v-if="hasLinks" class="links"><SocialLinks :person="person"/></div>
  </li>
</template>

<style scoped lang="scss">
@use '../assets/episode';

.person {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  column-gap: 16px;
  padding: 18px 0;
  border-top: 1px solid var(--rule);
}

@include episode.summary-row;

.summary, .roles {
  color: var(--secondary);
}

// Among the details, in their size
.code {
  font-size: 12px;
  line-height: 20px;
}

.details a {
  color: var(--secondary);

  &:hover {
    color: var(--accent-text);
  }
}

// On the name's line, which is 24px
.picture {
  margin-top: -8px;
}

// On phones the links go under the details, beside the picture
@media (max-width: 640px) {
  .person {
    grid-template-columns: 40px minmax(0, 1fr);
    padding: 16px 0 12px;
  }

  .links {
    grid-column: 2;
  }
}

</style>
