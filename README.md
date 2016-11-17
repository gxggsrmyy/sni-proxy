# Introduction

sni-proxy is an asynchronous event-driven sni proxy. It helps you create a sni proxy easily without setting up a mass dependencies. All you need is Java8 environment.

# Setup & Run
Listen on all network interfaces:
```
nohup java -Xmx100m -jar sni-proxy.jar  &>/dev/null &
```
Or listen on specified ip address:
```
nohup java -Xmx100m -jar sni-proxy.jar 127.0.0.1  &>/dev/null &
```
Modify your local hosts file, point www.google.com to your sni proxy ip:
```
x.x.x.x  www.google.com
```
Try to open google in your browser!
