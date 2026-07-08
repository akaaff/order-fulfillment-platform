# Plain static files behind nginx - no build step, matching the "no framework,
# no build tooling" choice for this client (see CLAUDE.md).
FROM nginx:1.29-alpine3.23
COPY index.html style.css app.js /usr/share/nginx/html/
EXPOSE 80
