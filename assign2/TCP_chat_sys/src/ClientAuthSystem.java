import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class ClientAuthSystem
{
    private final Map<String, UserCredentials> registeredUsers = new ConcurrentHashMap<>();
    private static final String FILE_PATH = "users.txt";

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

    //class to store salt and hashed password together
    private static class UserCredentials
    {
        private final String passwordHash;
        private final String salt;

        public UserCredentials(String passwordHash, String salt)
        {
            this.passwordHash = passwordHash;
            this.salt = salt;
        }

        public String getPasswordHash()
        {
            return passwordHash;
        }

        public String getSalt()
        {
            return salt;
        }
    }

    public ClientAuthSystem()
    {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            Future<?> future = executor.submit(this::loadUsersFromFile);

            try
            {
                future.get(OPERATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                System.err.println("Loading users took too long: " + e.getMessage());
                future.cancel(true);
            }
            catch (Exception e)
            {
                System.err.println("Error loading users: " + e.getMessage());
            }
        }
    }

    public boolean registerClient(String username, String plainTextPassword) throws NoSuchAlgorithmException
    {
        readLock.lock();

        try
        {
            if (registeredUsers.containsKey(username))
            {
                return false;
            }
        }
        finally
        {
            readLock.unlock();
        }

        UserCredentials credentials;
        try
        {
            // Generate a random salt
            SecureRandom random = new SecureRandom();
            byte[] saltBytes = new byte[16];
            random.nextBytes(saltBytes);
            String salt = Base64.getEncoder().encodeToString(saltBytes);

            String hashedPassword = hashPassword(plainTextPassword, salt);
            credentials = new UserCredentials(hashedPassword, salt);
        }
        catch (NoSuchAlgorithmException e)
        {
            System.err.println("Error hashing password: " + e.getMessage());
            return false;
        }

        registeredUsers.put(username, credentials);

        saveUserToFile(username, credentials);

        return true;
    }


    public boolean verifyClient(String username, String attemptedPassword) throws NoSuchAlgorithmException
    {
        UserCredentials credentials;
        readLock.lock();

        try
        {
            credentials = registeredUsers.get(username);

            if (credentials == null)
            {
                return false;
            }
        }
        finally
        {
            readLock.unlock();
        }

        try
        {
            String hashedAttempt = hashPassword(attemptedPassword, credentials.getSalt());
            return hashedAttempt.equals(credentials.getPasswordHash());
        }
        catch (NoSuchAlgorithmException e)
        {
            System.err.println("Error verifying password: " + e.getMessage());
            return false;
        }
    }

    private String hashPassword(String password, String salt) throws NoSuchAlgorithmException
    {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt.getBytes());
        byte[] hashedPassword = md.digest(password.getBytes());
        return Base64.getEncoder().encodeToString(hashedPassword);
    }

    private void loadUsersFromFile()
    {
        File file = new File(FILE_PATH);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] parts = line.split(":");

                if (parts.length == 3)
                {
                    String username = parts[0];
                    String hash = parts[1];
                    String salt = parts[2];

                    registeredUsers.put(username, new UserCredentials(hash, salt));
                }
            }
        }
        catch (IOException e)
        {
            System.out.println("Error reading user file: " + e.getMessage());
        }
    }

    private void saveUserToFile(String username, UserCredentials credentials)
    {
        System.out.println("Trying to save: " + username);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH, true)))
        {
            writer.write(username + ":" + credentials.getPasswordHash() + ":" + credentials.getSalt());
            writer.newLine();
            System.out.println("Saved user: " + username);
        }
        catch (IOException e)
        {
            System.out.println("Error saving user to file: " + e.getMessage());
        }
    }

    public boolean usernameExists(String username)
    {
        return registeredUsers.containsKey(username);
    }
}