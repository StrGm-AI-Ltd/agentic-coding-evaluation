# Static shell served by nginx. The React app is pre-built into public/ so the compose build
# stays hermetic (no npm install against the network during grading).
FROM nginx:1.27-alpine
COPY public/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
