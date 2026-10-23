<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import PersonAvatar from '@/components/PersonAvatar.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { roleName } from '@/episodes'
import { roleCounts } from '@/lists'
import { type Person, personPath } from '@/people'

// Someone on the People page. On the team, a card: their picture, large, and beside it their name, who they are, and how
// many episodes they've been on as what, the most first. Anyone else, a line: picture, name, who they are, their
// episodes, and where they're online.
const props = defineProps<{
  person: Person
  team?: boolean
}>()

// Host 46 · consultant 5, but not what they might be, a candidate
const roles = computed(() => roleCounts(props.person)
  .filter(({ role }) => role !== 'CANDIDATE')
  .map(({ role, count }) => `${roleName(role)} ${count}`)
  .join(' · '))

// Their latest episodes by code, and how many there are: hosts have dozens
const LATEST = 3
const latest = computed(() => props.person.episodes.slice(0, LATEST).map(({ episode }) => episode.code ?? episode.title))
</script>

<template>
  <li v-if="team" class="team-card">
    <PersonAvatar :image="person.avatar" :name="person.name" :size="72"/>
    <div class="text">
      <RouterLink class="name" :to="personPath(person)">{{ person.name }}</RouterLink>
      <p v-if="person.bio" class="about">{{ person.bio }}</p>
      <p class="roles">{{ roles }}</p>
    </div>
  </li>

  <li v-else class="person-card">
    <PersonAvatar :image="person.avatar" :name="person.name" :size="40"/>
    <div class="text">
      <RouterLink class="name" :to="personPath(person)">{{ person.name }}</RouterLink>
      <p v-if="person.bio" class="bio">{{ person.bio }}</p>
      <p v-if="person.episodes.length" class="episodes">
        <template v-if="person.episodes.length > 1">{{ person.episodes.length }} episodes · </template>
        <span class="codes">{{ latest.join(', ') }}</span>
      </p>
    </div>
    <span class="links"><SocialLinks :person="person"/></span>
  </li>
</template>

<style scoped lang="scss">

.name {
  display: block;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.text {
  min-width: 0;
}

// The team: a card each, the picture beside who they are
.team-card {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 16px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--hairline);
  border-radius: 12px;

  .name {
    font-size: 15px;
    line-height: 22px;
  }
}

// Who they are, two lines at most
.about {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 2px;
  font-size: 13px;
  line-height: 19px;
  color: var(--secondary);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.roles {
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 18px;
  color: var(--muted);
}

// Everyone else: a line each, in columns
.person-card {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding: 10px 0;
  border-top: 1px solid var(--rule);
}

.bio, .episodes {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.bio {
  font-size: 12.5px;
  line-height: 18px;
  color: var(--secondary);
}

.episodes {
  font-size: 12px;
  line-height: 18px;
  color: var(--muted);
}

.codes {
  font-family: var(--font-mono);
  font-size: 11.5px;
}

// Two at most, as they're icons in a narrow column; the person's page has them all
.links {
  display: flex;
  margin-right: var(--icon-link-inset);

  :deep(a:nth-child(n+3)) {
    display: none;
  }
}
</style>
