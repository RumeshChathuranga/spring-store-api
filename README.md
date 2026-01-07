# Introduction to Spring MVC

---

### How the Web Works

When browsing a website, a **client** (the browser) sends a request to a **server**. The server processes this and
returns a **response**. This exchange is governed by **HTTP** (HyperText Transfer Protocol).

#### Anatomy of HTTP

| Part               | Description                                  |
|--------------------|----------------------------------------------|
| **Request Method** | Specifies the action (GET, POST, PUT, DELETE).|
| **Request URL** | The address of the specific resource being requested.|
| **Request Headers** | Extra metadata like content type or authentication tokens.|
| **Request Body** | Optional data sent to the server, such as form submissions.|
| **Response Status Code** | Indicates success or failure (e.g., 200 OK, 404 Not Found).|
| **Response Headers** | Metadata describing the returned response.|
| **Response Body** | The actual content, such as HTML markup or JSON data.|

---

### Web Page Generation

Web pages are built using **HTML** (HyperText Markup Language) through two primary methods:

* **Server-Side Rendering (SSR):** The server generates the full HTML page and sends it to the client.

* **Client-Side Rendering (CSR):** The server sends raw JSON data, and the client uses JavaScript to generate the page
dynamically.

#### Key Definitions

* **JSON (JavaScript Object Notation):** A lightweight, human-readable format used to structure data, commonly used in
APIs.

* **API (Application Programming Interface):** A communication bridge that allows clients to send or request data to and
from a server.



---

### The MVC Pattern

Spring MVC organizes applications into three distinct parts:

1. **Model:** Represents the data and business logic, often mapped to database entities.

2. **View:** Defines the data display, often using template engines like Thymeleaf in traditional apps.

3. **Controller:** The coordinator that handles HTTP requests, processes data, and returns responses.



---

### Handling Requests in Spring MVC

Spring MVC utilizes specialized annotations to manage different types of web traffic:

* **`@Controller`**: Primarily used for returning HTML views.

* **`@RestController`**: Used for returning data; it automatically converts Java objects into JSON.

#### Code Examples

**1. Returning an HTML View**

```java

@Controller
public class HomeController {
    @RequestMapping("/")
    public String index(Model model) {
        model.addAttribute("name", "Mosh");
        return "index"; // Returns index.html
    }
}

```

**2. Returning JSON Data**

```java

@RestController
public class MessageController {
    @RequestMapping("/hello")
    public Message sayHello() {
        return new Message("Hello World!"); // Converted to JSON
    }
}

```

---
