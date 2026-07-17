# Plain static files behind nginx - no build step, matching the "no framework,
# no build tooling" choice for this client (see CLAUDE.md).
#
# nginx-unprivileged (not plain nginx): the stock nginx image's worker
# processes already drop to an unprivileged user, but its master process
# still starts as root to bind port 80 - a Trivy config scan flagged this
# Dockerfile for having no USER at all, which is correct: nothing here
# declares non-root intent, it's implicit in the base image's entrypoint
# script. nginx-unprivileged runs the whole process tree as non-root from
# the start, listens on 8080 instead of 80 (see web-client.yaml's matching
# containerPort/Service change), and is required for
# securityContext.runAsNonRoot: true (set in web-client.yaml) to be
# satisfiable at all.
FROM nginxinc/nginx-unprivileged:1.29-alpine3.23
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html style.css app.js /usr/share/nginx/html/
EXPOSE 8080
