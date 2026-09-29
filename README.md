Still a WIP but this is a chat app that is built on a SpringBoot backend and a basic react and javascript front end.
This features end to end encryption and utilizes a system in which 24 hours the backend data is dropped for security.
The tables are created automatically on first run, but the database and user must exist first. In the MySQL shell:

CREATE DATABASE chatapp;
CREATE USER 'chatapp'@'localhost' IDENTIFIED BY 'chatapp_pass';
GRANT ALL PRIVILEGES ON chatapp.* TO 'chatapp'@'localhost';

change user credentials if wanted

startup backend:
cd backend
./mvnw spring-boot:run
 
start react frontend:
cd frontend
npm install
npm run dev

it will open locally on port 5173 feel free to host locally after.