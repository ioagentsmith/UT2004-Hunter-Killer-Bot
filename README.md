UT2004 Hunter Killer Bot:
=========================

The bot(s) uses goal based learning, specifically kills per second and kill death ratio, 
to choose behaviours that lead to the best kills per minutes overall.

The main method in the ````HunterKotlinBot```` class is what runs the bots.
The ````HunterKotlinBot```` class extends the ```HunterKillerJavaBot``` class due to some generics
that do not work well in Kotlin yet.
 