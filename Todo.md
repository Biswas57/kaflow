
1. API interfaces
    - Implementing a HTTP Springboot interface for admin requestions from consumer and producer clients
    - Complete all Exceptions so that Spring can handle them as they bubble up.

    - Implementing a stream-like gRPC connection for message productions and consumption

                    ┌─────────────────────────────────────────────────┐
                    │              Spring-Boot Application            │
                    └─────────────────────────────────────────────────┘
        ┌──────────────┐  HTTP/JSON  ┌────────────────┐  in-proc  ┌─────────────┐
        │ Admin REST   │────────────▶│  AdminService  │──────────▶│             │
        │              │             │  (Controller)  │           │             │   
        │ Controller   │             └────────────────┘           │             │
        └──────────────┘                                          │   Core      │
                                                                  │ Tributary   │
        ┌──────────────┐  gRPC stream  ┌────────────────┐         │   Broker    │
        │  gRPC        │──────────────▶│ StreamService  │────────▶│             │
        │ Endpoints    │  (Produce /   │ (Produce /     │ in-proc │             │
        │ (grpc-java)  │   Subscribe)  │  Subscribe)    │         └─────────────┘
        └──────────────┘               └────────────────┘


2. the concurrency changes I need to make
  - Worker thread per topic/connection (up to you)
  - using synchronized write/read functions and thread safe list handling
  - eliminate parallel produce and parallel consume, because if we're gonna get a request in parallel we should do that and not just handle batch requests, batch requests are unlikely to happen in the microservice architecture we're aiming to be a part of
  - I'm not sure whether to use mutex locks or just Collections.synchronized list, design choice is upto you
  - I'm not sure if I have to use complete futures here or not
  - Use either Runnable interface or thread class for the worker thread running in the tributary controller
  - Not sure whether to have 1 thread per action or 1 thread per topic or IDK the relationship right now

3. Purge all implementations of RBAC
    - RBAC implementation is poorly implemented.
    - Replace it later on for actual RBAC implementation using Spring Security - Use Spring AuthService
    - RBAC is a good idea for this project, but should be implemented properly with Java's actual security features

4. When deleting components of the Tributary system, make sure to delete all relationships
    - SO that objects are not left dangling
    


Low Priority Changes:
1. Vectorized Topic creation future implementation -> Parse topicid in create event to existing topic and if not found use vectors to compare and make new one
    - Working on Functionality
    - Hashmap usage could be justified by iterating through keys (topicIDs) but what if its just a name and doesn't provide enough info for a vector comparison between the event being created and the topic
    - Point of this is to automate setup through the automation of creation methods

2. How do I deal with OOM issues when the parition is full
    - 1 way is Partition load balancing feature that auto detects excessive load on a topic/consumer group or redundate consumers in a consumer group and this can lead to autocreation of extra partitions in the topic and thus rebalancing the topic for consumer groups depending on their rebalancing strategy
    

- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit
    - Create API-connected CLI App acting kind of as a front end just to test API calls
    - Create UI using admin HTTP connections
    - create domains for both the stream and HTTP JSON connection
    - Set up docker container and host on my linux PC

- Update UML

