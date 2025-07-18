# 📋 CollabTodo - Lista de Tarefas Colaborativa

Este é o **CollabTodo**, um sistema de gerenciamento de tarefas com foco na **colaboração em tempo real** entre usuários. Desenvolvido com **Java + Spring Boot** no back-end e banco de dados **PostgreSQL**, esse projeto é ideal para equipes que precisam organizar e compartilhar listas de afazeres.

[![Java](https://img.shields.io/badge/Java-21-red.svg)](https://www.java.com)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-API-green.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-blue.svg)](https://www.postgresql.org/)
[![Status](https://img.shields.io/badge/Status-Em%20Desenvolvimento-yellow.svg)](https://github.com/brunodeev/collab-todo)

---

## ✨ Funcionalidades

- ✅ Cadastro de usuários com **verificação por e-mail** e **criptografia de senha**
- 🔐 Login seguro
- 📝 Criação de **listas de tarefas (ToDo Lists)**
- 📌 Criação e gerenciamento de **itens de tarefas**
- 👥 Suporte a múltiplos usuários por lista *(em desenvolvimento)*

---

## 🧠 Tecnologias Utilizadas

| Tecnologia | Descrição |
|------------|-----------|
| Java 21+   | Linguagem principal do back-end |
| Spring Boot | Framework para construção da API REST |
| PostgreSQL | Banco de dados relacional (via Supabase) |
| JavaMailSender | Envio de e-mails de confirmação |
| Maven      | Gerenciador de dependências |

---

## 🛠️ Endpoints disponíveis (até o momento)

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| `POST` | `/user` | Criação de usuário com envio de código por e-mail |
| `POST` | `/user/activate` | Validação do código enviado por e-mail |
| `GET` | `/user/login` | Login do usuário |
| `POST` | `/todo-list` | Criação de uma nova lista de tarefas |
| `POST` | `/todo-item` | Criação de uma nova tarefa vinculada a uma lista |

---

## 📨 Envio de E-mail

Após o registro, um código de ativação é enviado automaticamente para o e-mail do usuário. Esse código deve ser usado para validar a conta antes do primeiro login. O envio é realizado com `JavaMailSender` e templates HTML personalizados.

---

## 💻 Como rodar localmente

1. Clone o repositório:
```bash
git clone https://github.com/brunodeev/collab-todo.git
cd collab-todo
