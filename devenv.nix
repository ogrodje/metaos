{
  pkgs,
  lib,
  config,
  inputs,
  ...
}: let
  unstable = import inputs.unstable-nixpkgs {
    inherit (pkgs.stdenv) system;
    config.allowUnfree = true; 
  };
in {
  cachix.enable = false;
  name = "metaos";

  languages.java = {
    enable = true;
    jdk.package = unstable.jdk25_headless;
    gradle.enable = true;
    gradle.package = unstable.gradle_9;
    lsp.enable = true;
  };

  languages.javascript = {
    enable = true;
    directory = "./fe";
    package = unstable.nodejs_26;
    nodejs.enable = true;
    yarn.enable = true;
    # `yarn install` in fe/ on entering the shell, when yarn.lock has changed
    yarn.install.enable = true;
    yarn.package = unstable.yarn-berry;
  };

  # `devenv up`: Nuxt's dev server for fe/, which renders pages on the server as production does, with hot reload, on
  # http://localhost:5173, from MetaOS on :8080 (NUXT_METAOS_URL points it at another)
  processes.fe = {
    exec = "yarn dev";
    cwd = "${config.devenv.root}/fe";
  };

  packages = [
    pkgs.git
    pkgs.kubectl
    pkgs.kubectx
    pkgs.kubernetes-helm
    pkgs.kustomize
    pkgs.just
    # gcloud, for google/storage.sh
    pkgs.google-cloud-sdk
    # tofu, for infra/ (Keycloak's realm)
    unstable.opentofu
  ];

  env = {
    METAOS_ENV = "development";
  };

  enterShell = ''
  '';
}
