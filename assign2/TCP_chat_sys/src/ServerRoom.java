import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ServerRoom
{
    private static final int MAX_MESSAGE_COUNT = 5;
    private final String name;
    private final List<Socket> clients;
    private final List<PrintWriter> writers;

    private final ReadWriteLock lock;

    public ServerRoom(String name) {
        this.name = name;
        this.clients = new ArrayList<>();
        this.writers = new ArrayList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    public String getName()
    {
        return name;
    }

    public void addClient(Socket socket, PrintWriter writer)
    {
        lock.writeLock().lock();

        try
        {
            clients.add(socket);
            writers.add(writer);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }


    public boolean isEmpty()
    {
        lock.readLock().lock();

        try
        {
            return clients.isEmpty();
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    public void removeClient(Socket socket, PrintWriter writer)
    {
        File logFile = null;

        lock.writeLock().lock();

        try
        {
            clients.remove(socket);
            writers.remove(writer);

            boolean isCostumRoom = !getName().equals("general");

            if (clients.isEmpty() && isCostumRoom)
            {
                logFile = new File(name + "_log.txt");
            }
        }
        catch (Exception e)
        {
            System.out.println("Error removing client from room " + name + ": " + e.getMessage());
        }
        finally
        {
            lock.writeLock().unlock();
        }

        if (logFile != null && logFile.exists())
        {
            logFile.delete();
        }
    }

    public synchronized void broadcast(String message, PrintWriter sender)
    {
        try (PrintWriter pw = new PrintWriter(new FileWriter(name + "_log.txt", true), true))
        {
            pw.println(message);
        }
        catch (IOException e)
        {
            System.out.println("Error logging message in room " + name + ": " + e.getMessage());
        }

        for (PrintWriter writer : writers)
        {
            if (writer != sender)
            {
                writer.println(message);
            }
        }
    }

    public synchronized List<String> getLastFiveMessages()
    {
        List<String> lastMessages = new ArrayList<>();

        Path logFilePath = Paths.get(name + "_log.txt");

        if (!Files.exists(logFilePath))
        {
            System.out.println("Log file does not exist yet: " + logFilePath);
            return lastMessages;
        }

        try (BufferedReader reader = Files.newBufferedReader(logFilePath))
        {
            List<String> lines = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }

            int start = Math.max(0, lines.size() - MAX_MESSAGE_COUNT);

            for (int i = start; i < lines.size(); i++)
            {
                String message = lines.get(i);

                if (!message.startsWith("You:"))
                {
                    lastMessages.add(message);
                }
            }
        }
        catch (IOException e)
        {
            System.out.println("Error reading the message log: " + e.getMessage());
        }
        return lastMessages;
    }
}
