# brave-zipkin-spancollector #

[Brave](https://github.com/kristofa/brave) SpanCollector that converts Brave spans into [Zipkin](https://github.com/twitter/zipkin/) spans and submits them to a Zipkin collector.
Advantage is that you can reuse the Zipkin back-end (zipkin collector, Cassandra back-end store and zipkin web UI).

For information on how to set up the Zipkin backend components see [here](http://twitter.github.io/zipkin/install.html).

## No release available :-( ##

There is no release for brave-zipkin-spancollector available because it depends on third-party Maven repos 
(specified in the pom.xml and see below). Because of this I could not release it to Maven Central.

It is advised to add the required thirdy part Maven repos:

    <repositories>
        <repository>
            <id>scala-tools.org</id>
            <name>Scala-tools Maven2 Repository</name>
            <url>http://scala-tools.org/repo-releases</url>
            <releases><enabled>true</enabled></releases>                        
        </repository>
        <repository>
            <id>twitter</id>
            <url>http://maven.twttr.com/</url>
            <releases><enabled>true</enabled></releases>
        </repository>
        <repository>
            <id>mvnrepository</id>
            <url>http://mvnrepository.com/</url>
            <releases><enabled>true</enabled></releases>
        </repository>
    </repositories>
    
to your own proxy (Nexus, Artifactory) instead of leaving them in the pom.xml

## Deploy SNAPSHOT ##


    git clone https://github.com/kristofa/brave-zipkin-spancollector.git
    cd brave-zipkin-spancollector
    mvn deploy
    
This should work if your Maven ~/.m2/settings.xml configuration allows specifying third party
Maven repos besides those in settings.xml or if you have added the Maven repos to your own
proxy.


## Make a release ##

If you would want to use this span collector as part of a release you will have to add
release-plugin entry in pom.xml + specify scm and distributionManagement sections and do
a release into your own Maven repo.

    

    