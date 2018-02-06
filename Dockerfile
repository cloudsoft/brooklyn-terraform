FROM openjdk:8-jre-alpine
LABEL maintainer="Cloudsoft :: https://cloudsoft.io/"
EXPOSE 8081 8443
ARG AMP_TGZ_URL
ARG ADDITIONAL_BOMS
ARG DROPINS_JARS
ENV EXTRA_JAVA_OPTS="-XX:SoftRefLRUPolicyMSPerMB=1" 
RUN wget -q ${AMP_TGZ_URL} && mkdir /apache-brooklyn && tar -xzf *tar.gz -C /apache-brooklyn --strip-components=1 && rm *.tar.gz ; \
RUN apk --no-cache upgrade --update ; \
    apk --no-cache add bash openssl ca-certificates wget ; \
    if [ -n "${ADDITIONAL_BOMS}" ] ; then \
        echo "brooklyn.catalog:" > /apache-brooklyn/etc/default.catalog.bom ; \
        echo "  items:" >> /apache-brooklyn/etc/default.catalog.bom ; \
        for x in ${ADDITIONAL_BOMS} ; do \
        echo Installing $x to catalog ; \
        echo "  - "$x >> /apache-brooklyn/etc/default.catalog.bom ; \
        done ; \
    fi ; \
    if [ -n "${DROPINS_JARS}" ] ; then \
        mkdir -p /apache-brooklyn/lib/dropins/ ; \ 
        cd /apache-brooklyn/lib/dropins/ ; \
        for x in ${DROPINS_JARS} ; do \
        echo Download $x to /apache-brooklyn/lib/dropins ; \
        wget -q --trust-server-names $x ; \
        done ; \
    fi ; 
ENTRYPOINT ["/apache-brooklyn/bin/karaf"]
CMD ["server"]