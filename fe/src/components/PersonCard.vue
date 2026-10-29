<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import PersonAvatar from '@/components/PersonAvatar.vue'
import SocialLinks from '@/components/SocialLinks.vue'
import { teamSummary } from '@/lists'
import { type Person, personPath } from '@/people'

// Someone on the People page. On the team, a card: their picture, large, and beside it their name, who they are, and
// what they do on how many episodes. Anyone else, a line: picture, name, who they are, their latest episode and how
// many there are, and where they're online.
const props = defineProps<{
  person: Person
  team?: boolean
}>()

// 37 episodes as co-host, host and consultant
const roles = computed(() => teamSummary(props.person))

const latest = computed(() => props.person.episodes[0]?.episode)
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
      <p v-if="latest" class="episodes">
        <template v-if="person.episodes.length > 1">{{ person.episodes.length }} episodes · </template>
        <span lang="sl">{{ latest.title }}</span>
      </p>
    </div>
    <span class="links"><SocialLinks :person="person"/></span>
  </li>
</template>

<style scoped lang="scss">

.name {
  display: block;
  overflow: hidden;
  font-size: var(--text-body);
  line-height: 20px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.text {
  min-width: 0;
}

// The team: the picture large beside who they are, under a rule, as the guests' lines are
.team-card {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 16px;
  min-width: 0;
  padding: 16px 0;
  border-top: 1px solid var(--rule);

  .name {
    font-size: var(--text-lead);
    line-height: 22px;
  }
}

// Who they are, two lines at most
.about {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 2px;
  font-size: var(--text-small);
  line-height: 19px;
  color: var(--secondary);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.roles {
  margin-top: 4px;
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
  text-wrap: pretty;
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
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--secondary);
}

.episodes {
  font-size: var(--text-caption);
  line-height: 18px;
  color: var(--muted);
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
