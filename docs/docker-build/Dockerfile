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
  echo "provider \"azurerm\" {}" >> index.tf ; \
  echo "provider \"google\" {}" >> index.tf ; \
  echo "provider \"vsphere\" {}" >> index.tf ; \
  terraform init ; \
  cd .. ; \
  rm -rf tmp

# now configure container to use that cache but not try to populate it
# this is because the container is often run from scratch with a volume mounted \
# we need custom-downloaded providers to be in that volume
RUN cd ~ ; \
  echo -e '\
## cache plugins so that they are not repeatedly downloaded\n\
## and stored with lots of copies (as plugins can be big)\n\
## NOTE: you have to create this directory!\n\
#plugin_cache_dir   = \"$HOME/.terraform.d/plugin-cache\"\n\
\n\
## per https://developer.hashicorp.com/terraform/cli/config/config-file#allowing-the-provider-plugin-cache-to-break-the-dependency-lock-file\n\
#plugin_cache_may_break_dependency_lock_file = true\n\
\n\
# once the cache is populated we want to use it as a mirror only\n\
provider_installation {\n\
  filesystem_mirror {\n\
    path    = "/root/.terraform.d/plugin-cache"\n\
  }\n\
}\n\
' > .terraformrc

CMD ["/bin/sh"]
