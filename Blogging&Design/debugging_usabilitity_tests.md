# Debugging Usability Tests

# 1 topic no Admin flow
create topic banana integer

create partition banana bananaCookingMethod1

create partition banana bananaCookingMethod2

create partition banana bananaCookingMethod3

create partition banana bananaCookingMethod4

show topic banana

create producer bananaBoiler banana random

create producer bananaFrier banana manual

create consumer group bananaChefs banana range

create consumer bananaChefs beginnerChef1

create consumer bananaChefs beginnerChef2

create consumer bananaChefs beginnerChef3

show consumer group bananaChefs

update rebalancing bananaChefs roundRobin

show consumer group bananaChefs

create event bananaBoiler banana bananaBoilingNums

show topic banana

show consumer group bananaChefs

consume event beginnerChef1 bananaCookingMethod1

consume events beginnerChef2 bananaCookingMethod2 (whatever number is in it)

consume events beginnerChef3 bananaCookingMethod3 (number of messages in partition - 1)

consume event beginnerChef2 bananaCookingMethod2

consume event beginnerChef3 bananaCookingMethod3

update consumer offset beginnerChef1 bananaCookingMethod1 1

update consumer offset beginnerChef1 bananaCookingMethod4 0

parrallel produce bananaFrier banana bananaFryNums bananaCookingMethod1 bananaFrier banana bananaFryNums bananaCookingMethod4

show topic banana


# Multiple topics with Admin flow and Security features
