# Introduction

sni-proxy is an asynchronous event-driven sni proxy. It helps you create a sni proxy easily without setting up a mass dependencies. All you need is Java8 environment.

# Setup & Run
```
nohup java -Xmx100m -jar sni-proxy.jar  &>/dev/null &
```
Modify your local hosts file, point www.google.com to your sni proxy ip:
```
x.x.x.x  www.google.com
```
Try to open google in your browser!
