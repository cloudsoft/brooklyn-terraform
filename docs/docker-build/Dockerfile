FROM alpine:latest
MAINTAINER Iuliana Cosmina "iuliana.cosmina@apache.org"

RUN apk update && apk add --no-cache curl bash terraform unzip git

# configure container to cache plugins
RUN cd ~ ; \
  echo -e "\
# cache plugins so that they are not repeatedly downloaded\n\
# and stored with lots of copies (as plugins can be big)\n\
# NOTE: you have to create this directory!\n\
plugin_cache_dir   = \"$HOME/.terraform.d/plugin-cache\"\n\
\n\
# per https://developer.hashicorp.com/terraform/cli/config/config-file#allowing-the-provider-plugin-cache-to-break-the-dependency-lock-file\n\
plugin_cache_may_break_dependency_lock_file = true\n\
" > .terraformrc ; \
  mkdir -p .terraform.d/plugin-cache

# preload the AWS plugin
RUN cd ~ ; \
  mkdir tmp ; cd tmp ; \
  echo "provider \"aws\" {}" > index.tf ; \
  terraform init ; \
  cd .. ; \
  rm -rf tmp

CMD ["/bin/sh"]
