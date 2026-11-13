# ogrodje.si's and www's records, as Netlify has them (dns.tf), taken in once: the apply that does it changes nothing,
# and this file goes after it (OG-59)
import {
  to = cloudflare_dns_record.apex
  id = "524def828f49bea187dc3eae3d02fff8/38b17af769380aa5e7cd44c00611b105"
}

import {
  to = cloudflare_dns_record.www
  id = "524def828f49bea187dc3eae3d02fff8/fbc059c957934f9a12edd4cd89221f2c"
}
