import { defineEventHandler, setHeader } from 'h3'

// For the Kubernetes probes (k8s/base/deployment-web.yaml): the server answers. Whether MetaOS does is its own probes'.
export default defineEventHandler((event) => {
  setHeader(event, 'cache-control', 'no-store')
  return 'UP'
})
