# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Deploys the Hawtio console as a web application.

[tags]
3rdparty

[depend]
stats
deploy
jmx
javax-transformer

[xml]
etc/hawtio.xml

[files]
etc/hawtio/
lib/hawtio/
maven://io.hawt/hawtio-default/${hawtio.version}/war|lib/hawtio/hawtio.war
basehome:modules/hawtio/hawtio.xml|etc/hawtio.xml

[license]
Hawtio is a redhat JBoss project released under the Apache License, v2.0
http://hawt.io/
http://github.com/hawtio/hawtio
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
hawtio.version?=2.13.5

[ini-template]
## Hawt.io configuration
-Dhawtio.authenticationEnabled?=false
-Dhawtio.dirname?=/dirname
-Dhawtio.config.dir?=${jetty.base}/etc/hawtio
