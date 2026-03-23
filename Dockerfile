FROM eclipse-temurin:17-jre
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=prod

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
     libreoffice \
     fonts-noto-cjk \
     fonts-nanum \
     fontconfig \
  && rm -rf /var/lib/apt/lists/*

COPY fonts/malgun/ /usr/local/share/fonts/malgun/
RUN fc-cache -f -v

COPY app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
