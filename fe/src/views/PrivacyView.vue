<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useSwitchLocalePath } from '#imports'
import { rememberLocale } from '@/localeChoice'
import { usePageMeta } from '@/seo'
import { useLocale } from '@/useLocale'

// The privacy policy, at ogrodje.si's old address of it, which Google's sign-in consent screen links to: in Slovenian
// at /zasebnost, and in English at /en/zasebnost, each linking to the other, as the old site had them one after the
// other on one page. Its text is written in each language here, not in the messages, as it's legal text. What it says
// has to follow what MetaOS does with people's data, e.g. a new service that gets some of it, or a new thing kept in
// the browser, in both languages. Each links to the other, named in its own language, as the language switch names them.

const { t, locale } = useLocale()
const switchLocalePath = useSwitchLocalePath()
const route = useRoute()
const router = useRouter()

usePageMeta(() => ({ description: t('privacy.description') }))

// The English policy was #en of the Slovenian page, as links to it may still say
onMounted(() => {
  if (locale.value === 'sl' && route.hash === '#en') void router.replace(switchLocalePath('en'))
})
</script>

<template>
  <main class="content">
    <article v-if="locale === 'sl'" class="policy" lang="sl" aria-labelledby="policy">
      <header>
        <h1 id="policy">Politika zasebnosti</h1>
        <p class="meta">
          Velja od 13. novembra 2026.
          <RouterLink class="link" :to="switchLocalePath('en')" hreflang="en" lang="en" @click="rememberLocale('en')">
            In English
          </RouterLink>
        </p>
      </header>

      <p>
        Ta politika opisuje, katere osebne podatke Ogrodje obdeluje na spletni strani
        <a class="link" href="https://ogrodje.si">ogrodje.si</a>, ki jo poganja MetaOS, naše orodje za pripravo in
        upravljanje epizod, ob prijavi vanjo in v e-pošti Ogrodja.
      </p>

      <h2>Spletna stran ogrodje.si</h2>
      <p>Ne uporabljamo oglasov ali piškotkov za sledenje.</p>
      <p>
        Stran in MetaOS tečeta na strežnikih, ki jih upravlja Ogrodje. Pred njimi je Cloudflare, ki strani pošilja iz
        svojega omrežja in kot vsak spletni strežnik beleži tehnične podatke o zahtevah (na primer IP naslov in vrsto
        brskalnika). Slike epizod in ljudi se nalagajo z <a class="link" href="https://media.ogrodje.si">media.ogrodje.si</a>,
        naše shrambe v Google Cloud Storage, prav tako prek Cloudflara; logotipi organizatorjev na strani Dogodki pa s
        Hygraph, kjer jih hrani <a class="link" href="https://goo.ogrodje.si">Goo</a>.
      </p>
      <p>
        <strong>Delovanje strani.</strong> Za spremljanje napak in hitrosti uporabljamo New Relic, katerega skripta v
        brskalniku sporoči napake na strani, čas nalaganja in zahteve, ki jih stran pošlje MetaOS. V vašem brskalniku
        ne shrani ničesar, ne piškotkov ne drugih podatkov, podatke pa hrani v EU.
      </p>
      <p>
        <strong>V brskalniku.</strong> Brez prijave stran sama v vašem brskalniku shrani le jezik, ki ste ga izbrali, da se
        njene strani naslednjič odprejo v njem. Ko se prijavite,
        si strežnik za prijavo vašo prijavo zapomni v piškotku. Članom ekipe stran shrani še, da so bili prijavljeni
        (da ob vrnitvi ne utripa), in kdaj so nazadnje odprli posamezno epizodo, da vidijo, kaj je novega. Ti podatki
        ne zapustijo vaše naprave.
      </p>

      <h2>Prijava</h2>
      <p>
        Prijavite se prek našega strežnika za prijavo
        (<a class="link" href="https://metaos-auth.ogrodje.si">metaos-auth.ogrodje.si</a>), z Google ali Discord
        računom. Kdor se prijavi, postane član Ogrodja. Dostop do dela ekipe, do epizod v pripravi, idej in podobnega,
        članu ročno odobri skrbnik.
      </p>
      <p>
        <strong>Kaj prejmemo.</strong> Ob prijavi z Google računom prejmemo vaše ime, e-poštni naslov in identifikator
        Google računa. Ob prijavi z Discord računom prejmemo uporabniško ime, prikazno ime, e-poštni naslov in
        identifikator računa.
      </p>
      <p>
        <strong>Zakaj.</strong> Podatke uporabimo za prijavo, za odobritev dostopa ekipi in da vašo prijavo po
        preverjenem e-poštnem naslovu povežemo z osebo v naši evidenci gostov in sodelavcev.
      </p>
      <p>
        <strong>Kaj hranimo o članih.</strong> Vaš račun pri strežniku za prijavo (ime, uporabniško ime, e-poštni
        naslov in identifikatorje računov, s katerimi se prijavljate) in, ko imate dostop do dela ekipe, isti račun v
        MetaOS, povezan z vašo osebo. Prijava velja 30 dni brez uporabe in največ 90 dni. Članom ekipe zabeležimo
        vsako spremembo v MetaOS skupaj s tem, kdo jo je naredil, in komentarje, ki jih napišejo.
      </p>
      <p>
        <strong>Google Drive.</strong> Do vašega Google Drive, e-pošte ali drugih podatkov v vašem Google računu ne
        dostopamo. Dokumente Ogrodja v Google Drive MetaOS ureja prek ločenega Google računa Ogrodja, ki ima dostop le
        do map, ki jih z njim delimo.
      </p>
      <p>
        Uporaba podatkov, ki jih MetaOS prejme prek Google API-jev, je skladna s
        <a class="link" href="https://developers.google.com/terms/api-services-user-data-policy" hreflang="en">Google
        API Services User Data Policy</a>, vključno z zahtevami Limited Use.
      </p>

      <h2>E-pošta Ogrodja</h2>
      <p>
        Ogrodje si dopisuje tudi z naslova <a class="link" href="mailto:iva.ogrodje@gmail.com">iva.ogrodje@gmail.com</a>,
        Googlovega računa Ogrodja, ki ga uporablja MetaOS.
      </p>
      <p>
        <strong>Kaj obdelujemo.</strong> Sporočila, ki jih izmenjate s tem naslovom: pošiljatelja, prejemnike, zadevo,
        datum, besedilo in imena priponk.
      </p>
      <p>
        <strong>Zakaj.</strong> Za dogovarjanje z gosti in sodelavci, na primer o snemanju epizod. MetaOS sporočila bere
        ter v imenu Ogrodja pripravlja in pošilja odgovore; pri tem lahko ekipa uporablja orodja umetne inteligence, na
        primer Claude.
      </p>
      <p>
        <strong>Kje.</strong> Sporočila hrani Gmail. Tudi zanje velja, da je uporaba podatkov, ki jih MetaOS prejme
        prek Google API-jev, skladna z zahtevami Limited Use.
      </p>

      <h2>Komu posredujemo podatke</h2>
      <p>
        Osebnih podatkov ne prodajamo in jih ne uporabljamo za oglaševanje. MetaOS in strežnik za prijavo tečeta na
        strežnikih, ki jih upravlja Ogrodje. Pri prijavi sodelujeta Google oziroma Discord, ki ju izberete sami;
        e-pošto Ogrodja in naše datoteke hrani Google, zahteve na stran gredo prek Cloudflara, podatke o delovanju
        strani pa hrani New Relic.
      </p>

      <h2>Hramba in vaše pravice</h2>
      <p>
        Račun člana hranimo, dokler ste član; ko nas prosite za izbris, ga izbrišemo s strežnika za prijavo in iz
        MetaOS. Zgodovino sprememb hranimo kot evidenco dela na epizodah, tudi ko račun izbrišemo. Podatke o delovanju
        strani New Relic hrani omejen čas in jih nato izbriše. Sporočila hranimo, dokler jih potrebujemo za
        sodelovanje z vami; na zahtevo jih izbrišemo.
      </p>
      <p>
        Imate pravico do dostopa do svojih podatkov, njihovega popravka ali izbrisa, omejitve obdelave in ugovora. Za
        uveljavljanje pravic ali vprašanja nam pišite na
        <a class="link" href="mailto:otobrglez@gmail.com">otobrglez@gmail.com</a>. Pritožbo lahko vložite tudi pri
        <a class="link" href="https://www.ip-rs.si">Informacijskem pooblaščencu</a>.
      </p>
      <p>O spremembah te politike bomo obvestili na tej strani.</p>
    </article>

    <article v-else class="policy" lang="en" aria-labelledby="policy">
      <header>
        <h1 id="policy">Privacy policy</h1>
        <p class="meta">
          Effective 13 November 2026.
          <RouterLink class="link" :to="switchLocalePath('sl')" hreflang="sl" lang="sl" @click="rememberLocale('sl')">
            V slovenščini
          </RouterLink>
        </p>
      </header>

      <p>
        This policy describes which personal data Ogrodje processes on the website
        <a class="link" href="https://ogrodje.si">ogrodje.si</a>, which MetaOS, the tool we prepare and manage episodes
        with, runs, when you sign in to it, and in Ogrodje’s email.
      </p>

      <h2>The ogrodje.si website</h2>
      <p>We don’t use ads or tracking cookies.</p>
      <p>
        The site and MetaOS run on servers managed by Ogrodje. In front of them is Cloudflare, which serves the pages
        from its network and, like any web server, logs technical request data (such as IP address and browser type).
        Pictures of episodes and people load from <a class="link" href="https://media.ogrodje.si">media.ogrodje.si</a>,
        our storage in Google Cloud Storage, through Cloudflare too; the organizers’ logos on the Events page from
        Hygraph, where <a class="link" href="https://goo.ogrodje.si">Goo</a> keeps them.
      </p>
      <p>
        <strong>How the site works.</strong> We use New Relic to follow errors and speed: its script in the browser
        reports the page’s errors, how long it took to load and the requests it sends to MetaOS. It keeps nothing in
        your browser, neither cookies nor anything else, and New Relic stores the data in the EU.
      </p>
      <p>
        <strong>In your browser.</strong> Without signing in, the site itself keeps only the language you chose in your
        browser, so its pages open in it next time. Once you
        sign in, the sign-in server remembers your sign-in in a cookie. For the team, the site also keeps that they
        were signed in (so it doesn’t flicker when they come back) and when they last opened each episode, to show
        what’s new. None of it leaves your device.
      </p>

      <h2>Signing in</h2>
      <p>
        You sign in through our sign-in server
        (<a class="link" href="https://metaos-auth.ogrodje.si">metaos-auth.ogrodje.si</a>), with a Google or Discord
        account. Anyone who signs in becomes a member of Ogrodje. Access to the team’s work, the episodes in the works,
        the ideas and the like, an admin grants members by hand.
      </p>
      <p>
        <strong>What we receive.</strong> Signing in with Google gives us your name, email address and Google account
        ID. Signing in with Discord gives us your username, display name, email address and account ID.
      </p>
      <p>
        <strong>Why.</strong> We use them to sign you in, to grant the team’s access and to link your sign-in to a
        person in our records of guests and contributors by verified email address.
      </p>
      <p>
        <strong>What we keep about members.</strong> Your account at the sign-in server (name, username, email address
        and the IDs of the accounts you sign in with) and, once you have the team’s access, the same account in
        MetaOS, linked to the person you are. A sign-in lasts 30 days without use and 90 days at most. For the team, we
        record every change in MetaOS together with who made it, and the comments they write.
      </p>
      <p>
        <strong>Google Drive.</strong> We don’t access your Google Drive, email or any other data in your Google
        account. MetaOS edits Ogrodje’s documents in Google Drive through a separate Ogrodje Google account, which can
        only access the folders shared with it.
      </p>
      <p>
        MetaOS’s use of information received from Google APIs adheres to the
        <a class="link" href="https://developers.google.com/terms/api-services-user-data-policy">Google API Services User
        Data Policy</a>, including the Limited Use requirements.
      </p>

      <h2>Ogrodje’s email</h2>
      <p>
        Ogrodje also writes from <a class="link" href="mailto:iva.ogrodje@gmail.com">iva.ogrodje@gmail.com</a>,
        Ogrodje’s Google account that MetaOS uses.
      </p>
      <p>
        <strong>What we process.</strong> The messages you exchange with that address: sender, recipients, subject,
        date, text and the names of attachments.
      </p>
      <p>
        <strong>Why.</strong> To arrange things with guests and contributors, such as recording episodes. MetaOS reads
        the messages and drafts and sends replies on Ogrodje’s behalf; the team may use AI tools, such as Claude, to do
        so.
      </p>
      <p>
        <strong>Where.</strong> Gmail stores the messages. MetaOS’s use of information received from Google APIs
        adheres to the Limited Use requirements for them too.
      </p>

      <h2>Who we share data with</h2>
      <p>
        We don’t sell personal data or use it for advertising. MetaOS and the sign-in server run on servers managed by
        Ogrodje. Google or Discord, whichever you choose, take part in signing in; Google stores Ogrodje’s email and our
        files, requests to the site go through Cloudflare, and New Relic stores the data on how the site works.
      </p>

      <h2>Retention and your rights</h2>
      <p>
        We keep a member’s account for as long as they’re a member; when you ask us to delete it, we delete it from the
        sign-in server and from MetaOS. The change history is kept as the record of work on episodes, also once an
        account is deleted. New Relic keeps the data on how the site works for a limited time, then deletes it. We keep
        messages for as long as we need them to work with you, and delete them on request.
      </p>
      <p>
        You have the right to access, correct or delete your data, to restrict its processing and to object to it. To
        use these rights or ask a question, write to
        <a class="link" href="mailto:otobrglez@gmail.com">otobrglez@gmail.com</a>. You can also lodge a complaint with
        the <a class="link" href="https://www.ip-rs.si/en/">Slovenian Information Commissioner</a>.
      </p>
      <p>We will announce changes to this policy on this page.</p>
    </article>
  </main>
</template>

<style scoped lang="scss">
@use '../assets/page';

.link {
  @include page.link;
}

// Running text, at its measure
.policy {
  display: grid;
  gap: 12px;
  max-width: 680px;
  font-size: var(--text-body);
  line-height: 22px;

  header {
    display: grid;
    gap: 6px;
    padding-bottom: 8px;
  }

  h1 {
    font-size: var(--text-page);
    line-height: 34px;
    font-weight: 600;
    letter-spacing: -0.02em;
  }

  h2 {
    padding-top: 16px;
    font-size: var(--text-section);
    line-height: 26px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }

  .meta {
    color: var(--secondary);

    .link {
      margin-left: 8px;
    }
  }

  strong {
    font-weight: 600;
  }
}
</style>
