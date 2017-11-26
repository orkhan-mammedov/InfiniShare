JC = /usr/lib/jvm/java-7-oracle/bin/javac
.SUFFIXES: .java .class
.java.class:
	$(JC) $*.java

CLASSES = \
	Client.java \
	Server.java \
	CommunicationMessage.java \

default: classes

all: classes

classes: $(CLASSES:.java=.class)
	chmod +x server
	chmod +x client

clean:
	$(RM) *.class