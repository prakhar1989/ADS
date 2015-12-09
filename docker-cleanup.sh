#!/bin/bash

# source: http://blog.yohanliyanage.com/2015/05/docker-clean-up-after-yourself/
# clean up exited containers
docker rm -v $(docker ps -a -q -f status=exited)

# clean up dangling images
docker rmi $(docker images -f "dangling=true" -q)

# clean up volumes
docker run -v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/docker:/var/lib/docker --rm martin/docker-cleanup-volumes