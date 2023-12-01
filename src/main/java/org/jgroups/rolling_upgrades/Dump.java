package org.jgroups.rolling_upgrades;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;

import static io.grpc.Status.UNAVAILABLE;

/** Dumps the contents of the UpgradeServer's registered clusters and members
 * @author Bela Ban
 * @since 1.0.0
 */
public class Dump {
    protected ManagedChannel                                channel;
    protected UpgradeServiceGrpc.UpgradeServiceBlockingStub blocking_stub;


    protected void start(String host, int port) throws InterruptedException {
        channel=ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        blocking_stub=UpgradeServiceGrpc.newBlockingStub(channel);
        DumpResponse response=blocking_stub.dump(Void.newBuilder().build());
        System.out.printf("%s\n", response.getDump());
    }


    protected void stop() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws InterruptedException {
        String host="localhost";
        int port=50051;
        Dump client=new Dump();
        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-host")) {
                host=args[++i];
                continue;
            }
            if(args[i].equals("-port")) {
                port=Integer.parseInt(args[++i]);
                continue;
            }
            System.out.println("Dump [-host host] -port port] [-h]");
            return;
        }
        try {
            client.start(host, port);
        }
        catch(StatusRuntimeException sre) {
            Status status=sre.getStatus();
            if(UNAVAILABLE.getCode() == status.getCode())
                System.err.printf("failed to connect to server %s:%d (not running?)\n", host, port);
            else
                System.err.printf("failed to connect to server %s:%d: %s\n", host, port, status);
        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
        finally {
            client.stop();
        }
    }
}
