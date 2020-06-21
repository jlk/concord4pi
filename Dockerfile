FROM openjdk:15-jdk-alpine
COPY . /usr/src/concord4pi
WORKDIR /usr/src/concord4pi
RUN apk update && \
    apk add apache-ant && \
    sed -i 's/= file/= console/' config/log.config && \
    ant  
ENTRYPOINT /usr/src/concord4pi/startup/entrypoint.sh
