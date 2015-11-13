echo "----"
echo "setting up the master"
sudo docker rm master
export MASTER_ID=$(sudo docker run --name master -d adb-rethinkdb rethinkdb --bind all)
export MASTER_IP=$(sudo docker inspect --format '{{ .NetworkSettings.IPAddress }}' master)

for i in {1..3}
do
  echo "------"
  echo "setting up slave server"
  echo "------"
  SLAVE_ID=slave$i
  sudo docker rm $SLAVE_ID
  sudo docker run --name $SLAVE_ID -d adb-rethinkdb rethinkdb --join $MASTER_IP:29015 --bind all
done

echo "--- CLUSTER STATUS ---"
sudo docker ps
