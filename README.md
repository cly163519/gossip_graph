GossipGraph

Visualize character relationships and timelines from Chinese or English drama text.

* Idea

This project started from my own interest in historical/romance dramas such as Empresses in the Palace (甄嬛传).
I wanted a tool to read plain text and show who loves, supports, or betrays whom, and to create a simple timeline of events.

* What It Does

Extracts relationships from natural-language text (Chinese or English) using regex/NLP rules

Builds a directed graph with edge styles for love, support, betray, etc.

Exports Graphviz .dot and .png files

Supports simple event timelines (YYYY-MM-DD description)

* Development Notes

Language: Java

Libraries: JGraphT, Graphviz

Code generation: I designed the requirements and logic myself, and used AI coding assistance for some implementation details.

* Quick Start
javac com/laura/Main.java
java com.laura.Main graph --text="A loves B. B betrayed C." --out=graph.dot --png=graph.png
java com.laura.Main timeline --file=events.txt
