# The front-end (fe/): Nuxt's server, which renders the pages, from `./gradlew feBuild` (fe/.output), on distroless Node.
# As with the app's jar, it's built outside (just push, .github/workflows/deploy.yml): .output has all it runs, its
# node_modules too, none of them native, so building the linux/amd64 image on an Apple silicon machine is a copy.
FROM gcr.io/distroless/nodejs26-debian13:nonroot
WORKDIR /app
COPY fe/.output/ ./

# The runtime config (nuxt.config.ts) comes from NUXT_* variables (k8s/base/deployment-web.yaml)
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000
EXPOSE 3000/tcp
CMD ["server/index.mjs"]
