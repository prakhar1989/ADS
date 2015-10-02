Project Proposal
===

### Topic
Evaluating consistency semantics of **RethinkDB** - an upcoming real-time open-source database.

### Background
RethinkDB is a scalable documented oriented database which came out of a need to make building real-time applications easier. Apart from being one of the few NoSQL databases that allow joins, RethinkDB is a distributed that is easy to scale. With multiple shards and replicas, the database aims to provide high availability and robust fault-tolerance.

### Research Question / Project Goals
- Analyze the consistency properties of RethinkDB
- Evaluate API semantics around consistency and availability guarantees as documented on the website

### Methodology
- Setup a RethinkDB cluster with one shards, multiple replicas
- Design test-cases for validating each semantic
- Use the Jepsen clojure library to run test-cases, mimic network partitions and model failures etc.
- Validate results of operations and derive conclusions.

### Team Members
- Prakhar Srivastav (ps2894)
- Ayush Jain (aj2672)

[PPT](https://docs.google.com/presentation/d/1K19MUX96BfwQVA9RYon5WFyZgQT-FsIS2t436csjRN4/pub?start=false&loop=false&delayms=3000)
